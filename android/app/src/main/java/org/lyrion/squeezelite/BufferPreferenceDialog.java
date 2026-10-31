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

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.google.android.material.slider.Slider;

/** Buffer size choices, with the dynamic buffer's maximum size slider shown while that one is selected. */
public class BufferPreferenceDialog extends PreferenceDialogFragmentCompat {
    private RadioGroup sizes;
    private View dynamicOptions;
    private TextView maxSizeLabel;
    private Slider maxSize;

    public static BufferPreferenceDialog newInstance(String key) {
        BufferPreferenceDialog dialog = new BufferPreferenceDialog();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
        super.onBindDialogView(view);
        BufferPreference pref = (BufferPreference) getPreference();
        sizes = view.findViewById(R.id.buffer_sizes);
        dynamicOptions = view.findViewById(R.id.dynamic_options);
        maxSizeLabel = view.findViewById(R.id.max_size_label);
        maxSize = view.findViewById(R.id.max_size);

        CharSequence[] entries = pref.getEntries();
        CharSequence[] values = pref.getEntryValues();
        int selected = pref.findIndexOfValue(pref.getValue());
        for (int i = 0; i < entries.length; ++i) {
            RadioButton button = new RadioButton(view.getContext());
            button.setId(View.generateViewId());
            button.setText(entries[i]);
            button.setTag(values[i].toString());
            sizes.addView(button, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == selected) {
                button.setChecked(true);
            }
        }

        int max = 256;
        try {
            max = Integer.parseInt(pref.getSharedPreferences().getString(Prefs.LOOKAHEAD_MAX_KEY, Prefs.DEFAULT_LOOKAHEAD_MAX));
        } catch (NumberFormatException ignored) {
        }
        maxSize.setValue(max >= 512 ? 512 : 256);

        sizes.setOnCheckedChangeListener((group, id) -> updateDynamicOptions());
        maxSize.addOnChangeListener((slider, value, fromUser) -> updateDynamicOptions());
        updateDynamicOptions();
    }

    private String selectedValue() {
        View button = sizes.findViewById(sizes.getCheckedRadioButtonId());
        return null == button ? null : (String) button.getTag();
    }

    private void updateDynamicOptions() {
        dynamicOptions.setVisibility(Prefs.STREAM_BUFFER_DYNAMIC.equals(selectedValue()) ? View.VISIBLE : View.GONE);
        maxSizeLabel.setText(getString(R.string.lookahead_max, (int) maxSize.getValue()));
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        String value = selectedValue();
        if (!positiveResult || null == value) {
            return;
        }
        BufferPreference pref = (BufferPreference) getPreference();
        // The maximum first: the summary shown for the dynamic buffer includes it
        pref.getSharedPreferences().edit().putString(Prefs.LOOKAHEAD_MAX_KEY, String.valueOf((int) maxSize.getValue())).apply();
        if (pref.callChangeListener(value)) {
            pref.setValue(value);
        }
    }
}
