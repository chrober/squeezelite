/*
 *  Squeezelite Android
 *
 *  (c) Craig Drummond 2025-2026 <craig.p.drummond@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.lyrion.squeezelite;

enum AndroidAutoSessionAction {
    PAUSE,
    RESUME,
    STOP;

    static AndroidAutoSessionAction forSettings(boolean autostart, boolean pauseOnSessionChange) {
        if (autostart) {
            return STOP;
        }
        return pauseOnSessionChange ? PAUSE : RESUME;
    }

    static boolean resumeOnConnection(boolean pauseOnSessionChange) {
        return !pauseOnSessionChange;
    }

    static boolean reclaimOnSessionStart(boolean resumeOnConnection, boolean pausedByFocusLoss) {
        return resumeOnConnection && pausedByFocusLoss;
    }

    static boolean retriesFocusRecovery(boolean pausedByFocusLoss, int carConnectionState,
                                        long now, long deadline) {
        return pausedByFocusLoss && CarConnection.NOT_CONNECTED==carConnectionState && now<deadline;
    }
}
