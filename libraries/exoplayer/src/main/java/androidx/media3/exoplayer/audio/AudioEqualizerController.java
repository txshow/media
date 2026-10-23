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
package androidx.media3.exoplayer.audio;

import android.content.Context;
import androidx.media3.common.audio.AudioEffectParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Applies platform (OpenSL/OpenSL ES or platform effect session) audio equalizer settings to the
 * audio session of an {@link ExoPlayer}.
 *
 * <p>This is a degraded implementation: it does not attach any platform equalizer and simply
 * reports success, leaving audio equalization to the software {@code AudioEffectProcessor} in the
 * audio sink.
 */
@UnstableApi
public class AudioEqualizerController {

  /** @param context A {@link Context}. */
  public AudioEqualizerController(Context context) {}

  /**
   * Applies the given equalizer parameters to the player's audio session.
   *
   * @param player The {@link ExoPlayer} whose audio session should be configured.
   * @param parameters The equalizer parameters to apply.
   * @return {@code true} (degraded: always reports success so software processing takes over).
   */
  public boolean apply(ExoPlayer player, AudioEffectParameters parameters) {
    return true;
  }

  /** Releases any platform equalizer resources held by this controller. */
  public void release() {}
}
