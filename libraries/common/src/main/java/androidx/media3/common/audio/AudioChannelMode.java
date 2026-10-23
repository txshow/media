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
package androidx.media3.common.audio;

import androidx.media3.common.util.UnstableApi;

/**
 * Utilities for the user-selectable audio channel remix mode.
 *
 * <p>The mode is stored as an int to allow it to be persisted and passed through the audio
 * pipeline without referencing this class on every call.
 */
@UnstableApi
public final class AudioChannelMode {

  /** Automatic: no forced remix (resolved to a concrete mode by {@link #resolve}). */
  public static final int AUTO = 0;

  /** Force stereo output. */
  public static final int STEREO = 1;

  /** Force mono output. */
  public static final int MONO = 2;

  /** Force stereo output with left/right channels reversed. */
  public static final int REVERSE = 3;

  private AudioChannelMode() {}

  /**
   * Resolves {@link #AUTO} to a concrete mode for the given channel count. Explicit modes are
   * returned unchanged.
   *
   * @param channelMode The raw channel mode.
   * @param channelCount The number of input channels.
   * @return The resolved concrete mode.
   */
  public static int resolve(int channelMode, int channelCount) {
    int mode = clamp(channelMode);
    if (mode == AUTO) {
      return channelCount >= 2 ? STEREO : MONO;
    }
    return mode;
  }

  /**
   * Returns whether channel remixing (a channel mix matrix) is available for the given number of
   * channels.
   *
   * @param channelCount The number of channels.
   * @return Whether a channel mix can be built for this channel count.
   */
  public static boolean isAvailable(int channelCount) {
    return channelCount >= 2 && channelCount <= 8;
  }

  /**
   * Returns whether the given mode is available for the given channel count.
   *
   * @param channelMode The channel mode.
   * @param channelCount The number of input channels.
   * @return Whether the mode can be applied.
   */
  public static boolean isAvailable(int channelMode, int channelCount) {
    int mode = clamp(channelMode);
    switch (mode) {
      case AUTO:
        return true;
      case STEREO:
      case REVERSE:
        return channelCount >= 2;
      case MONO:
        return channelCount >= 1;
      default:
        return false;
    }
  }

  /**
   * Returns whether the given mode is {@link #AUTO}.
   *
   * @param channelMode The channel mode.
   * @return Whether the mode is automatic.
   */
  public static boolean isAuto(int channelMode) {
    return clamp(channelMode) == AUTO;
  }

  /**
   * Returns whether the given mode supports left/right balance adjustment.
   *
   * @param channelMode The channel mode.
   * @return Whether balance can be applied.
   */
  public static boolean canBalance(int channelMode) {
    int mode = clamp(channelMode);
    return mode == STEREO || mode == REVERSE;
  }

  /**
   * Clamps an arbitrary int to one of the known channel mode constants. Unknown values fall back
   * to {@link #AUTO}.
   *
   * @param channelMode The channel mode.
   * @return The clamped mode.
   */
  public static int clamp(int channelMode) {
    switch (channelMode) {
      case AUTO:
      case STEREO:
      case MONO:
      case REVERSE:
        return channelMode;
      default:
        return AUTO;
    }
  }
}
