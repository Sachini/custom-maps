/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.custommapsapp.android.storage;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.custommapsapp.android.CustomMaps;
import com.custommapsapp.android.CustomMapsApp;
import com.custommapsapp.android.MemoryUtil;
import com.custommapsapp.android.R;
import com.custommapsapp.android.language.Linguist;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * SettingsFragment is PreferenceFragment for Custom Maps. Currently supported preferences:
 * <ul>
 * <li> isMetric (bool) - selects between metric and English units
 * <li> distanceDisplay (bool) - selects if distance to center of map is displayed
 * <li> headingDisplay (bool) - selects if heading to center of map is displayed (requires distance)
 * <li> safetyReminder (bool) - selects if safety reminder is shown when a map is opened
 * <li> color32bit (bool) - selects if 32 bit color should be used (may limit image size)
 * <li> useGpu (bool) - selects if GPU acceleration is used (limits image size severely)
 * <li> language (string) - allows user to override system language preference
 * </ul>
 *
 * @author Marko Teittinen
 */
public class SettingsFragment extends PreferenceFragmentCompat {
  private static final String PREFIX = "com.custommapsapp.android";
  public static final String LANGUAGE_CHANGED = PREFIX + ".LanguageChanged";

  private Activity activity;
  private Preference imageSizeInfo;

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    activity = getActivity();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getPreferenceManager().setSharedPreferencesName(PreferenceStore.SHARED_PREFS_NAME);
    reloadUI();
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
  }

  private void reloadUI() {
    setPreferenceScreen(createPreferenceScreen());
    // heading to screen center can be shown only with distance
    getPreferenceScreen().findPreference(PreferenceStore.PREFS_SHOW_HEADING)
        .setDependency(PreferenceStore.PREFS_SHOW_DISTANCE);
  }

  private PreferenceScreen createPreferenceScreen() {
    final Linguist linguist = ((CustomMapsApp) activity.getApplication()).getLinguist();
    if (getActivity() != null) {
      ((EditPreferences) getActivity()).updateTitle(linguist);
    }

    // Root
    PreferenceScreen root = getPreferenceManager().createPreferenceScreen(activity);

    // Units preference
    CheckBoxPreference isMetric = new CheckBoxPreference(activity);
    isMetric.setDefaultValue(PreferenceStore.isMetricLocale());
    isMetric.setKey(PreferenceStore.PREFS_METRIC);
    isMetric.setTitle(linguist.getString(R.string.metric_title));
    isMetric.setSummaryOff(linguist.getString(R.string.metric_use_english));
    isMetric.setSummaryOn(linguist.getString(R.string.metric_use_metric));
    root.addPreference(isMetric);

    // Display distance to center of screen
    CheckBoxPreference distanceDisplay = new CheckBoxPreference(activity);
    distanceDisplay.setDefaultValue(false);
    distanceDisplay.setKey(PreferenceStore.PREFS_SHOW_DISTANCE);
    distanceDisplay.setTitle(linguist.getString(R.string.distance_title));
    distanceDisplay.setSummaryOn(linguist.getString(R.string.distance_show));
    distanceDisplay.setSummaryOff(linguist.getString(R.string.distance_hide));
    root.addPreference(distanceDisplay);

    CheckBoxPreference headingDisplay = new CheckBoxPreference(activity);
    headingDisplay.setDefaultValue(false);
    headingDisplay.setKey(PreferenceStore.PREFS_SHOW_HEADING);
    headingDisplay.setTitle(linguist.getString(R.string.heading_title));
    headingDisplay.setSummaryOn(linguist.getString(R.string.heading_show));
    headingDisplay.setSummaryOff(linguist.getString(R.string.heading_hide));
    root.addPreference(headingDisplay);

    // Usage of 32-bit color in bitmaps
    CheckBoxPreference color32bit = new CheckBoxPreference(activity);
    color32bit.setDefaultValue(PreferenceStore.getArgb8888Default());
    color32bit.setKey(PreferenceStore.PREFS_USE_ARGB_8888);
    color32bit.setTitle(linguist.getString(R.string.argb_8888_title));
    color32bit.setSummaryOn(linguist.getString(R.string.argb_8888_summary_on));
    color32bit.setSummaryOff(linguist.getString(R.string.argb_8888_summary_off));
    root.addPreference(color32bit);

    // GPU acceleration
    CheckBoxPreference useGpu = new CheckBoxPreference(activity);
    useGpu.setDefaultValue(PreferenceStore.getGpuDefault());
    useGpu.setKey(PreferenceStore.PREFS_USE_GPU);
    useGpu.setTitle(linguist.getString(R.string.use_gpu_title));
    useGpu.setSummaryOn(linguist.getString(R.string.use_gpu_summary_on));
    useGpu.setSummaryOff(linguist.getString(R.string.use_gpu_summary_off));
    useGpu.setOnPreferenceChangeListener((preference, newValue) -> {
      updateImageSizeInfo(linguist, (Boolean) newValue);
      return true;
    });
    root.addPreference(useGpu);

    // Display language selection option
    Preference language = createLanguagePreference(linguist);
    root.addPreference(language);

    // Maximum image size info
    imageSizeInfo = createImageSizeInfo(linguist);
    if (imageSizeInfo != null) {
      root.addPreference(imageSizeInfo);
    }

    return root;
  }


  private ListPreference createLanguagePreference(Linguist linguist) {
    ListPreference language = new ListPreference(activity);
    language.setKey(PreferenceStore.PREFS_LANGUAGE);
    language.setTitle(linguist.getString(R.string.language_title));
    // Create a list of all available languages
    List<Locale> languages = new ArrayList<>();
    languages.add(Locale.ENGLISH);
    // Create display and value arrays, use "default" as first entry
    String[] languageNames = new String[languages.size() + 1];
    String[] languageCodes = new String[languages.size() + 1];
    languageNames[0] = linguist.getString(R.string.language_default);
    languageCodes[0] = "default";
    int idx = 1;
    StringBuilder buf = new StringBuilder();
    for (Iterator<Locale> iter = languages.iterator(); iter.hasNext(); idx++) {
      Locale locale = iter.next();
      // Make sure the first character of the language name is uppercase
      buf.setLength(0);
      buf.append(locale.getDisplayLanguage(locale));
      buf.setCharAt(0, Character.toUpperCase(buf.charAt(0)));
      languageNames[idx] = buf.toString();

      languageCodes[idx] = locale.getLanguage();
    }
    language.setEntryValues(languageCodes);
    language.setEntries(languageNames);
    // Create summary value showing current language
    language.setDefaultValue(languageCodes[0]);
    String selected = PreferenceStore.instance(activity).getLanguage();
    if (selected == null || selected.length() != 2) {
      selected = languageNames[0];
    } else {
      Locale tmp = new Locale(selected);
      selected = tmp.getDisplayLanguage(tmp);
    }
    language.setSummary(linguist.getString(R.string.language_selected_format, selected));
    // Prepare dialog
    language.setDialogTitle(linguist.getString(R.string.language_title));
    language.setOnPreferenceChangeListener((preference, newValue) -> {
      Log.d(CustomMaps.LOG_TAG, "PrefsFrag language changed to: " + newValue);
      // Update language used by app on user selection
      CustomMapsApp app = (CustomMapsApp) activity.getApplication();
      String languageCode = (String) newValue;
      app.changeLanguage(languageCode);
      PreferenceStore.instance(null).setLanguage(languageCode);
      activity.getIntent().putExtra(LANGUAGE_CHANGED, true);

      reloadUI();
      return true;
    });
    return language;
  }

  private Preference createImageSizeInfo(Linguist linguist) {
    Preference imageSizeInfo = new Preference(activity);
    imageSizeInfo.setSelectable(false);
    imageSizeInfo.setTitle(linguist.getString(R.string.max_map_img_size_title));
    if (PreferenceStore.instance(activity).isUseGpu()) {
      imageSizeInfo.setSummary(linguist.getString(R.string.max_map_img_size_gpu_on));
    } else {
      float megaPixels = MemoryUtil.getMaxImagePixelCount(activity) / 1E6f;
      imageSizeInfo.setSummary(linguist.getString(R.string.max_map_img_size, megaPixels));
    }
    return imageSizeInfo;
  }

  private void updateImageSizeInfo(Linguist linguist, boolean useGpu) {
    if (useGpu) {
      imageSizeInfo.setSummary(linguist.getString(R.string.max_map_img_size_gpu_on));
    } else {
      float megaPixels = MemoryUtil.getMaxImagePixelCount(activity) / 1E6f;
      imageSizeInfo.setSummary(linguist.getString(R.string.max_map_img_size, megaPixels));
    }
  }
}
