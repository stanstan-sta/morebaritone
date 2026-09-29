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
import baritone.api.BaritoneAPI;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.ISleepInBedProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.task.TaskOutcome;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finds an accessible bed, navigates to it, and initiates sleep.
 *
 * <p>
 * Internal state machine:
 * <ol>
 * <li>{@code SCANNING} – searching loaded chunks for bed blocks.</li>
 * <li>{@code PATHING} – moving toward the closest found bed.</li>
 * <li>{@code INTERACTING} – in range; issuing right-click until sleeping or
 * timeout.</li>
 * <li>{@code DONE} – terminal state; {@link #lastOutcome()} is populated.</li>
 * </ol>
 *
 * <h3>Night-time detection</h3>
 * Sleep is only allowed between day-time ticks 12542 and 23458 in the
 * overworld.
 * The process checks this condition both at start and just before the
 * interaction attempt.
 */
public final class SleepInBedProcess extends BaritoneProcessHelper
        implements ISleepInBedProcess, AbstractGameEventListener {

    /** Radius (in blocks) used for the nearby bed scan. */
    private static final int SCAN_RADIUS = 64;

    /** Maximum right-click ticks before declaring {@code TIMEOUT}. */
    private static final int INTERACT_TIMEOUT_TICKS = 60;

    /** Maximum path-calc failures before declaring {@code UNREACHABLE}. */
    private static final int MAX_CALC_FAILURES = 3;

    /** Vanilla requires the player to be within ~3 blocks of a bed to sleep.
     *  Squared distance threshold (3^2) for proximity checks. */
    private static final double SLEEP_PROXIMITY_SQ = 9.0;

    /** All 16 bed block variants for scanning. */
    private static final List<net.minecraft.world.level.block.Block> BED_BLOCKS = Arrays.asList(
            Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.MAGENTA_BED,
            Blocks.LIGHT_BLUE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
            Blocks.PINK_BED, Blocks.GRAY_BED, Blocks.LIGHT_GRAY_BED,
            Blocks.CYAN_BED, Blocks.PURPLE_BED, Blocks.BLUE_BED,
            Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.RED_BED,
            Blocks.BLACK_BED);

    private enum Phase {
        SCANNING, PATHING, INTERACTING, DONE
    }

    private Phase phase;
    private BlockPos targetBed; // specific bed we are heading to
    private List<BlockPos> candidates; // all found beds (may be refined)
    private TaskOutcome outcome;
    private int interactTick;
    private int calcFailCount;
    private boolean specificTarget; // true when caller supplied a position
    private String pendingBridgeTaskToken;
    private String activeBridgeTaskToken;

    public SleepInBedProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    // ─── ISleepInBedProcess ───────────────────────────────────────────────────

    @Override
    public void sleepInBed() {
        reset();
        this.specificTarget = false;
        logDirect("SleepInBed: scanning for beds");
    }

    @Override
    public void sleepInBed(BlockPos bedPos) {
        reset();
        this.targetBed = bedPos;
        this.specificTarget = true;
        this.phase = Phase.PATHING;
        logDirect("SleepInBed: targeting bed at " + bedPos);
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
        return phase != null && phase != Phase.DONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase == null || phase == Phase.DONE) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Night-time check (only meaningful in the overworld; skipped in the
        // nether / end where sleeping would explode or do nothing useful)
        if (!isNightOrThunder()) {
            logDirect("SleepInBed: not night – cannot sleep");
            finish(TaskOutcome.NOT_NIGHT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        switch (phase) {
            case SCANNING:
                return tickScanning();
            case PATHING:
                return tickPathing(calcFailed, isSafeToCancel);
            case INTERACTING:
                return tickInteracting();
            default:
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
    }

    @Override
    public void onLostControl() {
        if (isActive()) finish(TaskOutcome.CANCELLED);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        if (phase == Phase.SCANNING)
            return "SleepInBed: scanning";
        if (targetBed != null)
            return "SleepInBed → " + targetBed;
        return "SleepInBed";
    }

    // ─── AbstractGameEventListener (death handling) ───────────────────────────

    @Override
    public void onPlayerDeath() {
        if (isActive()) {
            logDirect("SleepInBed: player died – aborting");
            finish(TaskOutcome.PLAYER_DIED);
        }
    }

    // ─── phase tick helpers ───────────────────────────────────────────────────

    private PathingCommand tickScanning() {
        candidates = findNearbyBeds();
        if (candidates.isEmpty()) {
            logDirect("SleepInBed: no beds found within " + SCAN_RADIUS + " blocks");
            finish(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // Pick the closest bed as the initial target
        BetterBlockPos pf = ctx.playerFeet();
        candidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
        targetBed = candidates.get(0);
        phase = Phase.PATHING;
        calcFailCount = 0;
        logDirect("SleepInBed: found " + candidates.size() + " bed(s), targeting " + targetBed);
        return new PathingCommand(buildGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickPathing(boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed) {
            calcFailCount++;
            if (candidates != null && !candidates.isEmpty()) {
                // Try the next closest candidate
                candidates.remove(targetBed);
                if (!candidates.isEmpty()) {
                    BetterBlockPos pf = ctx.playerFeet();
                    candidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
                    targetBed = candidates.get(0);
                    calcFailCount = 0;
                    logDirect("SleepInBed: trying next bed candidate " + targetBed);
                    return new PathingCommand(buildGoal(),
                            PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                }
            }
            if (calcFailCount >= MAX_CALC_FAILURES) {
                logDirect("SleepInBed: all beds unreachable");
                finish(TaskOutcome.UNREACHABLE);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        // Vanilla requires ~3 blocks proximity to sleep – verify before attempting
        if (ctx.playerFeet().distSqr(targetBed) > SLEEP_PROXIMITY_SQ) {
            return new PathingCommand(buildGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Check if we are now in interaction range
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetBed,
                getInteractionReachDistance());
        if (reachable.isPresent()) {
            // Check occupied state before attempting to sleep
            BlockState state = ctx.world().getBlockState(targetBed);
            if (state.getBlock() instanceof BedBlock) {
                if (state.getValue(BedBlock.OCCUPIED)) {
                    logDirect("SleepInBed: bed at " + targetBed + " is occupied");
                    if (candidates != null) {
                        candidates.remove(targetBed);
                        if (!candidates.isEmpty()) {
                            BetterBlockPos pf = ctx.playerFeet();
                            candidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
                            targetBed = candidates.get(0);
                            calcFailCount = 0;
                            return new PathingCommand(buildGoal(),
                                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                        }
                    }
                    finish(TaskOutcome.OCCUPIED);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            phase = Phase.INTERACTING;
            interactTick = 0;
            logDirect("SleepInBed: reached bed, attempting interaction");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (targetBed == null) {
            logDirect("SleepInBed: no target bed available");
            finish(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        return new PathingCommand(buildGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickInteracting() {
        // Re-check night (could have changed while pathing)
        if (!isNightOrThunder()) {
            logDirect("SleepInBed: no longer night while interacting");
            finish(TaskOutcome.NOT_NIGHT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Vanilla requires ~3 blocks proximity to sleep
        if (ctx.playerFeet().distSqr(targetBed) > SLEEP_PROXIMITY_SQ) {
            // Too far – fall back to pathing
            phase = Phase.PATHING;
            calcFailCount = 0;
            return new PathingCommand(buildGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetBed,
                getInteractionReachDistance());
        if (!reachable.isPresent()) {
            // Moved out of range – fall back to pathing
            phase = Phase.PATHING;
            calcFailCount = 0;
            return new PathingCommand(buildGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        baritone.getLookBehavior().updateTarget(reachable.get(), true);

        if (interactTick++ >= INTERACT_TIMEOUT_TICKS) {
            logDirect("SleepInBed: interaction timed out");
            finish(TaskOutcome.TIMEOUT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (ctx.isLookingAt(targetBed)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);

            // Player entered the sleeping state
            if (ctx.player().isSleeping()) {
                logDirect("SleepInBed: now sleeping");
                finish(TaskOutcome.SUCCEEDED);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ─── world scan helpers ───────────────────────────────────────────────────

    /**
     * Scans loaded chunks within {@link #SCAN_RADIUS} for any bed block.
     * Only the foot-half of each bed is returned (to avoid duplicate entries
     * for the same physical bed).
     */
    private List<BlockPos> findNearbyBeds() {
        // Cache-first; if the cache is cold, scan only currently loaded chunks
        // within the 64-block radius instead of walking a ~2M-block cube on
        // the client tick thread.
        List<BlockPos> cached = findCachedBeds();
        if (!cached.isEmpty()) return cached;

        BetterBlockPos pf = ctx.playerFeet();
        int minY = Math.max(ctx.world().getMinY(), pf.y - SCAN_RADIUS);
        int maxY = Math.min(ctx.world().getMaxY(), pf.y + SCAN_RADIUS + 1);
        int chunkRadius = Math.max(1, (SCAN_RADIUS + 15) / 16);
        List<BlockPos> scanned = BaritoneAPI.getProvider().getWorldScanner()
                .scanChunkRadius(ctx, BED_BLOCKS, 256, -1, chunkRadius);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : scanned) {
            if (Math.abs(pos.getX() - pf.x) > SCAN_RADIUS
                    || Math.abs(pos.getZ() - pf.z) > SCAN_RADIUS
                    || pos.getY() < minY || pos.getY() >= maxY) continue;
            BlockState state = ctx.world().getBlockState(pos);
            if (BED_BLOCKS.contains(state.getBlock()) && state.getValue(BedBlock.PART) == BedPart.FOOT) {
                result.add(pos);
            }
        }
        return result;
    }

    // Cache-first bed lookup shared with the brute-force fallback above.
    // Cached positions are verified live (foot half, still a bed, in range)
    // so stale entries can never produce a phantom bed.
    private List<BlockPos> findCachedBeds() {
        List<BlockPos> r = new ArrayList<>();
        try {
            var cachedWorld = baritone.getWorldProvider().getCurrentWorld().getCachedWorld();
            if (cachedWorld == null) return r;
            BetterBlockPos pf = ctx.playerFeet();
            int minY = Math.max(ctx.world().getMinY(), pf.y - SCAN_RADIUS);
            int maxY = Math.min(ctx.world().getMaxY(), pf.y + SCAN_RADIUS + 1);
            for (net.minecraft.world.level.block.Block bed : BED_BLOCKS) {
                String name = BlockUtils.blockToString(bed);
                for (BlockPos pos : cachedWorld.getLocationsOf(name, Integer.MAX_VALUE, pf.x, pf.z, 2)) {
                    if (Math.abs(pos.getX() - pf.x) > SCAN_RADIUS
                            || Math.abs(pos.getZ() - pf.z) > SCAN_RADIUS
                            || pos.getY() < minY || pos.getY() >= maxY) continue;
                    BlockState state = ctx.world().getBlockState(pos);
                    if (state.getBlock() == bed && state.getValue(BedBlock.PART) == BedPart.FOOT) r.add(pos);
                }
            }
        } catch (Exception ignored) {
            // Cache unavailable or registries not ready: caller falls back
            // to the brute-force scan.
        }
        return r;
    }

    /**
     * Returns {@code true} if the world's day-time is within the sleeping
     * window, or if a thunderstorm is currently active (which also enables
     * sleep).
     */
    private boolean isNightOrThunder() {
        long dayTime = ctx.world().getDayTime() % 24000L;
        return SleepWindow.canSleep(dayTime, ctx.world().isThundering());
    }

    /**
     * Builds a {@link GoalGetToBlock} for the current target bed.
     * If there are multiple candidates a {@link GoalComposite} is used.
     */
    private baritone.api.pathing.goals.Goal buildGoal() {
        if (targetBed == null) {
            return null;
        }
        if (candidates != null && candidates.size() > 1) {
            return new GoalComposite(candidates.stream()
                    .map(GoalGetToBlock::new)
                    .toArray(baritone.api.pathing.goals.Goal[]::new));
        }
        return new GoalGetToBlock(targetBed);
    }

    private double getInteractionReachDistance() {
        return Math.max(0.0, ctx.playerController().getBlockReachDistance() - 0.1);
    }

    // ─── internal helpers ─────────────────────────────────────────────────────

    private void reset() {
        if (isActive()) finish(TaskOutcome.CANCELLED);
        activeBridgeTaskToken = pendingBridgeTaskToken;
        pendingBridgeTaskToken = null;
        baritone.getInputOverrideHandler().clearAllKeys();
        phase = Phase.SCANNING;
        targetBed = null;
        candidates = null;
        outcome = null;
        interactTick = 0;
        calcFailCount = 0;
        specificTarget = false;
    }

    private void finish(TaskOutcome out) {
        String token = activeBridgeTaskToken;
        activeBridgeTaskToken = null;
        this.outcome = out;
        this.phase = Phase.DONE;
        this.targetBed = null;
        this.candidates = null;
        baritone.getInputOverrideHandler().clearAllKeys();
        if (out == TaskOutcome.SUCCEEDED) {
            logDirect("All queued tasks complete");
            if (token != null) logBridgeStatus("Bridge task complete: " + token);
        } else {
            String status = out == TaskOutcome.CANCELLED ? "cancelled" : "failed";
            logDirect("Task " + status + ": sleep - " + out.toWireString());
            if (token != null) logBridgeStatus("Bridge task " + status + ": " + token + " - " + out.toWireString());
        }
    }

    @baritone.KeepName
    public void setBridgeTaskToken(String token) {
        pendingBridgeTaskToken = token;
    }

    public void reportCommandFailure(String reason) {
        String token = pendingBridgeTaskToken;
        pendingBridgeTaskToken = null;
        logDirect("Task failed: sleep - " + reason);
        if (token != null) logBridgeStatus("Bridge task failed: " + token + " - " + reason);
    }

    private void logBridgeStatus(String message) {
        logDirect(message, false);
    }
}
