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
 * Builds and composes per-channel audio mix matrices.
 *
 * <p>Each returned matrix has shape {@code [channelCount][channelCount]} (output rows x input
 * columns). Matrices can be composed with {@link #compose(float[][], float[][])} to chain several
 * mixes.
 *
 * <p>Channel indices follow the common PCM order: {@code 0 = front-left}, {@code 1 =
 * front-right}, {@code 2 = front-center} (when present).
 */
@UnstableApi
public final class AudioChannelMix {

  private AudioChannelMix() {}

  /**
   * Returns an identity mix (each channel maps to itself).
   *
   * @param channelCount The number of channels.
   */
  public static float[][] createIdentityMix(int channelCount) {
    float[][] mix = new float[channelCount][channelCount];
    for (int i = 0; i < channelCount; i++) {
      mix[i][i] = 1f;
    }
    return mix;
  }

  /**
   * Returns a mix that scales the front-center channel by the given factor.
   *
   * @param channelCount The number of channels.
   * @param factor The linear gain applied to the center channel.
   */
  public static float[][] createFrontCenterGainMix(int channelCount, float factor) {
    float[][] mix = createIdentityMix(channelCount);
    int center = centerChannelIndex(channelCount);
    if (center >= 0) {
      mix[center][center] = factor;
    }
    return mix;
  }

  /**
   * Returns a stereo mix, optionally swapping left and right. Other channels pass through.
   *
   * @param channelCount The number of channels.
   * @param reverse Whether to swap left and right.
   */
  public static float[][] createStereoMix(int channelCount, boolean reverse) {
    float[][] mix = createIdentityMix(channelCount);
    if (channelCount >= 2) {
      mix[0][0] = reverse ? 0f : 1f;
      mix[0][1] = reverse ? 1f : 0f;
      mix[1][0] = reverse ? 1f : 0f;
      mix[1][1] = reverse ? 0f : 1f;
    }
    return mix;
  }

  /**
   * Returns a mono mix: every output channel is the average of the front-left and front-right
   * inputs.
   *
   * @param channelCount The number of channels.
   */
  public static float[][] createMonoMix(int channelCount) {
    float[][] mix = new float[channelCount][channelCount];
    for (int i = 0; i < channelCount; i++) {
      if (channelCount >= 1) {
        mix[i][0] = 0.5f;
      }
      if (channelCount >= 2) {
        mix[i][1] = 0.5f;
      }
    }
    return mix;
  }

  /**
   * Returns a left/right balance mix.
   *
   * @param channelCount The number of channels.
   * @param balance The balance in [-1, 1]; negative shifts left, positive shifts right.
   */
  public static float[][] createFrontBalanceMix(int channelCount, float balance) {
    float[][] mix = createIdentityMix(channelCount);
    if (channelCount >= 2) {
      float leftGain = balance <= 0f ? 1f : 1f - balance;
      float rightGain = balance >= 0f ? 1f : 1f + balance;
      mix[0][0] = leftGain;
      mix[1][1] = rightGain;
    }
    return mix;
  }

  /**
   * Composes two mixes by matrix multiplication: {@code result = first * second}.
   *
   * @param first The first (outer) mix.
   * @param second The second (inner) mix.
   * @return The composed matrix.
   */
  public static float[][] compose(float[][] first, float[][] second) {
    int rows = first.length;
    int cols = second[0].length;
    int inner = second.length;
    float[][] result = new float[rows][cols];
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < cols; j++) {
        float sum = 0f;
        for (int k = 0; k < inner; k++) {
          sum += first[i][k] * second[k][j];
        }
        result[i][j] = sum;
      }
    }
    return result;
  }

  private static int centerChannelIndex(int channelCount) {
    if (channelCount < 3) {
      return -1;
    }
    // Front-center sits at index 2 for 3+ channel PCM layouts.
    return 2;
  }
}
