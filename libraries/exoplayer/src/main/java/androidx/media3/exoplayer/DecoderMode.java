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

import androidx.media3.common.util.UnstableApi;

/**
 * Decoder selection mode for an audio or video track, controlling whether platform or extension
 * decoders are preferred.
 */
@UnstableApi
public enum DecoderMode {
  /** Let the player choose the most appropriate decoder. */
  AUTO,
  /** Prefer software (extension) decoders. */
  SOFTWARE,
  /** Prefer hardware (platform MediaCodec) decoders. */
  HARDWARE,
  /** Force the FFmpeg extension decoders. */
  FFMPEG;

  /**
   * Returns the exoplayer {@link DecoderMode} matching the given common {@link
   * androidx.media3.common.DecoderMode}.
   */
  public static DecoderMode fromCommonDecoderMode(androidx.media3.common.DecoderMode mode) {
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

  /** Returns the common {@link androidx.media3.common.DecoderMode} matching this mode. */
  public androidx.media3.common.DecoderMode toCommonDecoderMode() {
    switch (this) {
      case SOFTWARE:
        return androidx.media3.common.DecoderMode.SOFTWARE;
      case HARDWARE:
        return androidx.media3.common.DecoderMode.HARDWARE;
      case FFMPEG:
        return androidx.media3.common.DecoderMode.FFMPEG;
      case AUTO:
      default:
        return androidx.media3.common.DecoderMode.AUTO;
    }
  }
}
