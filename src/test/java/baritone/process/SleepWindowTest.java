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

import org.junit.Test;

import static junit.framework.TestCase.*;

public class SleepWindowTest {

    @Test
    public void nightWindowAllowsSleep() {
        assertTrue(SleepWindow.canSleep(12542L, false));
        assertTrue(SleepWindow.canSleep(18000L, false));
        assertTrue(SleepWindow.canSleep(23458L, false));
    }

    @Test
    public void dawnAndDayDoNotAllowSleep() {
        // Previously the predicate had no upper bound, so morning ticks
        // counted as night and the bot tried to sleep when vanilla refuses.
        assertFalse(SleepWindow.canSleep(23459L, false));
        assertFalse(SleepWindow.canSleep(23999L, false));
        assertFalse(SleepWindow.canSleep(0L, false));
        assertFalse(SleepWindow.canSleep(6000L, false));
        assertFalse(SleepWindow.canSleep(12541L, false));
    }

    @Test
    public void thunderAllowsSleepAtAnyTime() {
        assertTrue(SleepWindow.canSleep(6000L, true));
        assertTrue(SleepWindow.canSleep(0L, true));
        assertTrue(SleepWindow.canSleep(23999L, true));
    }

    @Test
    public void dayTimeWrapsSafely() {
        assertTrue(SleepWindow.canSleep(24000L + 18000L, false));
        assertFalse(SleepWindow.canSleep(24000L + 6000L, false));
    }
}
