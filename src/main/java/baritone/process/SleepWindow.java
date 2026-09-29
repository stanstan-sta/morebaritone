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

/**
 * Vanilla sleep window shared by the bed processes.
 *
 * Sleeping is allowed between day-time ticks {@link #NIGHT_START_TICK} and
 * {@link #NIGHT_END_TICK}, or at any time while a thunderstorm is active.
 * Pure Java (no Minecraft classes) so the boundary is unit-testable.
 */
public final class SleepWindow {
    public static final long NIGHT_START_TICK = 12542L;
    public static final long NIGHT_END_TICK = 23458L;

    private SleepWindow() {}

    public static boolean canSleep(long dayTime, boolean thundering) {
        long t = ((dayTime % 24000L) + 24000L) % 24000L;
        return (t >= NIGHT_START_TICK && t <= NIGHT_END_TICK) || thundering;
    }
}
