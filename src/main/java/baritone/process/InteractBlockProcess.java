/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IInteractBlockProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.task.TaskOutcome;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.Optional;

/**
 * Navigates to a given block position and performs a right-click interaction.
 *
 * <p>Internal state machine:
 * <ol>
 *   <li>{@code PATHING} – using {@link GoalGetToBlock} to approach the target.</li>
 *   <li>{@code INTERACTING} – in range; issuing right-click until success or
 *       timeout.</li>
 *   <li>{@code DONE} – terminal state; {@link #lastOutcome()} is populated.</li>
 * </ol>
 */
public final class InteractBlockProcess extends BaritoneProcessHelper
        implements IInteractBlockProcess, AbstractGameEventListener {

    /** Maximum right-click ticks before declaring {@code TIMEOUT}. */
    private static final int INTERACT_TIMEOUT_TICKS = 40;

    /** Maximum path-calc failures before declaring {@code UNREACHABLE}. */
    private static final int MAX_CALC_FAILURES = 3;

    private enum Phase { PATHING, INTERACTING, DONE }

    private BlockPos target;
    private Phase phase;
    private TaskOutcome outcome;
    private int interactTick;
    private int calcFailCount;

    public InteractBlockProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    // ─── IInteractBlockProcess ────────────────────────────────────────────────

    @Override
    public void interactWithBlock(BlockPos target) {
        onLostControl(); // reset previous state
        this.target = target;
        this.phase = Phase.PATHING;
        this.outcome = null;
        this.interactTick = 0;
        this.calcFailCount = 0;
        logDirect("InteractBlock: targeting " + target);
    }

    @Override
    public TaskOutcome lastOutcome() {
        return outcome;
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.DONE;
    }

    // ─── IBaritoneProcess ─────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        return target != null && phase != Phase.DONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase == Phase.DONE || target == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (phase == Phase.PATHING) {
            if (calcFailed) {
                calcFailCount++;
                if (calcFailCount >= MAX_CALC_FAILURES) {
                    logDirect("InteractBlock: unreachable after " + calcFailCount + " failures");
                    finish(TaskOutcome.UNREACHABLE);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }

            Optional<Rotation> reachable = RotationUtils.reachable(ctx, target,
                    ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                // In range – switch to interaction phase
                phase = Phase.INTERACTING;
                interactTick = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } else {
                return new PathingCommand(new GoalGetToBlock(target),
                        PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
        }

        if (phase == Phase.INTERACTING) {
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, target,
                    ctx.playerController().getBlockReachDistance());
            if (!reachable.isPresent()) {
                // Moved out of range; go back to pathing
                calcFailCount = 0;
                phase = Phase.PATHING;
                return new PathingCommand(new GoalGetToBlock(target),
                        PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }

            baritone.getLookBehavior().updateTarget(reachable.get(), true);

            if (interactTick++ >= INTERACT_TIMEOUT_TICKS) {
                logDirect("InteractBlock: interaction timed out");
                finish(TaskOutcome.TIMEOUT);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            // Actually looking at the block?
            if (ctx.isLookingAt(target)) {
                // On the first tick we look at the block, issue the right-click.
                // We give a 3-tick window to detect a container opening (chests,
                // furnaces, etc.).  For non-container interactions (doors, buttons,
                // beds, levers) we simply confirm success after the click was sent.
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);

                // A non-inventory container opened → definitive success
                if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                    logDirect("InteractBlock: interaction succeeded (container opened)");
                    baritone.getInputOverrideHandler().clearAllKeys();
                    finish(TaskOutcome.SUCCEEDED);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }

                // After a short window without a container, assume the click was
                // delivered to a non-GUI block (door, button, bed, lever, …).
                if (interactTick >= 4) {
                    logDirect("InteractBlock: interaction sent (no container opened)");
                    baritone.getInputOverrideHandler().clearAllKeys();
                    finish(TaskOutcome.SUCCEEDED);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }

            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override
    public void onLostControl() {
        target = null;
        phase = Phase.DONE;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "InteractBlock " + target;
    }

    // ─── AbstractGameEventListener (death handling) ───────────────────────────

    @Override
    public void onPlayerDeath() {
        if (isActive()) {
            logDirect("InteractBlock: player died – aborting");
            finish(TaskOutcome.PLAYER_DIED);
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void finish(TaskOutcome out) {
        this.outcome = out;
        this.phase = Phase.DONE;
        this.target = null;
        baritone.getInputOverrideHandler().clearAllKeys();
    }
}
