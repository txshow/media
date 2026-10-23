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
package androidx.media3.mpvplayer;

import androidx.media3.common.DecoderMode;

/**
 * Video decoder selection for mpv playback, mirroring {@link DecoderMode}.
 *
 * <p>Use {@link #fromCommonDecoderMode(DecoderMode)} to convert the common API value and {@link
 * #toCommonDecoderMode()} to convert back.
 */
public enum MpvDecoderMode {
  /** Let mpv choose the decoder pipeline. */
  AUTO,
  /** Force software decoding. */
  SOFTWARE,
  /** Force hardware decoding. */
  HARDWARE,
  /** Use the bundled ffmpeg software decoder. */
  FFMPEG;

  public static MpvDecoderMode fromCommonDecoderMode(DecoderMode mode) {
    switch (mode) {
      case SOFTWARE:
        return SOFTWARE;
      case HARDWARE:
        return HARDWARE;
      case FFMPEG:
        return FFMPEG;
      case AUTO:
      default:
        return AUTO;
    }
  }

  public DecoderMode toCommonDecoderMode() {
    switch (this) {
      case SOFTWARE:
        return DecoderMode.SOFTWARE;
      case HARDWARE:
        return DecoderMode.HARDWARE;
      case FFMPEG:
        return DecoderMode.FFMPEG;
      case AUTO:
      default:
        return DecoderMode.AUTO;
    }
  }
}
