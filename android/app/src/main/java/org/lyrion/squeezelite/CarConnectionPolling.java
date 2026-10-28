/*
 *  Squeezelite Android
 *
 *  (c) Vincent Verdeil 2026 <werdeil@hotmail.com>
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

final class CarConnectionPolling {
    // Some Android Auto versions do not notify provider observers on disconnect.
    private static final long ACTIVE_INTERVAL = 1000;
    private static final long IDLE_INTERVAL = 30000;

    private CarConnectionPolling() {
    }

    static long interval(boolean connected, boolean playing) {
        return connected || playing ? ACTIVE_INTERVAL : IDLE_INTERVAL;
    }
}
