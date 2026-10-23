/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.text;

import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;

/**
 * A secondary {@link TextOutput} that receives cues for a secondary (e.g. libass-rendered) text
 * track.
 *
 * <p>This is a downgrade/no-op implementation: cues are received but not dispatched to a
 * downstream view. The {@link TextRenderer} constructed with this output still drives the player's
 * text-track selection and timing, but renders nothing on its own.
 */
@UnstableApi
public class SecondaryTextOutput implements TextOutput {

  /** Creates a new instance. */
  public SecondaryTextOutput() {}

  @Override
  public void onCues(CueGroup cueGroup) {
    // Downgrade: drop cues. Actual rendering is expected to be handled by libass.
  }
}
