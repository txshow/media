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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * Defines the decoder selection mode for a media track.
 *
 * <p>This is the common (module-agnostic) decoder mode. Module-specific implementations (e.g. in
 * the exoplayer or mpvplayer modules) map between this and their own decoder mode enums.
 */
@UnstableApi
public enum DecoderMode {
  /** Let the player choose the most appropriate decoder. */
  AUTO,

  /** Force software decoding. */
  SOFTWARE,

  /** Force hardware decoding. */
  HARDWARE,

  /** Force decoding through the FFmpeg extension decoder. */
  FFMPEG
}
