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
package androidx.media3.exoplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/** Provides additional ExoPlayer information used by debug views. */
@UnstableApi
public interface ExoPlayerDebugInfo {

  /** Returns the audio decoder currently being used, or null if no audio decoder is active. */
  @Nullable
  String getAudioDecoderName();

  /** Returns whether an {@code AudioTrack} output is currently initialized. */
  default boolean isAudioTrackInitialized() {
    return false;
  }

  /** Returns the video decoder currently being used, or null if no video decoder is active. */
  @Nullable
  String getVideoDecoderName();

  /** Returns the current video output object, or null if no video output is attached. */
  @Nullable
  Object getVideoOutput();
}
