/*
 *  Squeezelite Android
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
 *
 */

package org.lyrion.squeezelite;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

/**
 * The buffer size list, shown by BufferPreferenceDialog: a choice of sizes, plus the dynamic buffer
 * (Prefs.STREAM_BUFFER_DYNAMIC) with its maximum size per song (Prefs.LOOKAHEAD_MAX_KEY) in the same dialog.
 */
public class BufferPreference extends ListPreference {
    public BufferPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setDialogLayoutResource(R.layout.buffer_preference_dialog);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
    }
}
