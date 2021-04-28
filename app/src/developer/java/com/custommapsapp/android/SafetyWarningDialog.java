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
package com.custommapsapp.android;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;

/**
 * SafetyWarningDialog displays a warning to the user about likely inaccuracy of
 * the map images used by Custom Maps. The user must press OK to advance.
 *
 * @author Marko Teittinen
 */
public class SafetyWarningDialog extends Dialog {
  private CheckBox showAgain;
  private boolean buttonPressed = false;

  public SafetyWarningDialog(Context context) {
    super(context);
    setCancelable(true);
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    setContentView(R.layout.safetywarning);
    showAgain = findViewById(R.id.showAgain);

    Button okButton = findViewById(R.id.okButton);
    okButton.setOnClickListener(v -> {
      buttonPressed = true;
      dismiss();
    });
  }

  public View getRootView() {
    return findViewById(R.id.root_view);
  }

  public boolean wasButtonPressed() {
    return buttonPressed;
  }

  /**
   * State of "show again?" CheckBox. Should be ignored if OK button was not
   * used to close the dialog.
   *
   * @return boolean indicating the selection status of "show again?" CheckBox
   */
  public boolean getShowAgainRequested() {
    return showAgain.isChecked();
  }

  @Override
  protected void onStart() {
    super.onStart();
    buttonPressed = false;
    showAgain.setChecked(true);
  }
}
