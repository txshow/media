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
 * Immutable parameters describing the audio effects to apply: equalizer bands, stability
 * compressor, boost/preamp, center-channel gain, balance, channel remix mode and loudness
 * normalization.
 */
@UnstableApi
public final class AudioEffectParameters {

  /** A default (no-op) parameters instance with {@link #hasEffect()} == false. */
  public static final AudioEffectParameters DEFAULT = new Builder().build();

  private final int[] frequencies;
  private final short[] levels;
  private final float stability;
  private final int boostMillibels;
  private final int preampMillibels;
  private final int centerGainMillibels;
  private final float balance;
  private final int channelMode;
  private final boolean loudnessEnabled;

  private AudioEffectParameters(Builder builder) {
    this.frequencies = builder.frequencies == null ? new int[0] : builder.frequencies.clone();
    this.levels = builder.levels == null ? new short[0] : builder.levels.clone();
    this.stability = builder.stability;
    this.boostMillibels = builder.boostMillibels;
    this.preampMillibels = builder.preampMillibels;
    this.centerGainMillibels = builder.centerGainMillibels;
    this.balance = builder.balance;
    this.channelMode = builder.channelMode;
    this.loudnessEnabled = builder.loudnessEnabled;
  }

  /** Returns whether any effect is configured. */
  public boolean hasEffect() {
    return hasBands()
        || boostMillibels != 0
        || preampMillibels != 0
        || stability > 0
        || balance != 0
        || centerGainMillibels != 0
        || loudnessEnabled;
  }

  /** Returns whether equalizer bands are configured. */
  public boolean hasBands() {
    return levels.length > 0;
  }

  /** Returns the resolved channel remix mode. */
  public int getChannelMode() {
    return channelMode;
  }

  /** Returns the left/right balance, in the range [-1, 1]. */
  public float getBalance() {
    return balance;
  }

  /** Returns whether loudness normalization is enabled. */
  public boolean isLoudnessEnabled() {
    return loudnessEnabled;
  }

  /** Returns the boost gain, in millibels. */
  public int getBoostMillibels() {
    return boostMillibels;
  }

  /** Returns the preamp gain, in millibels. */
  public int getPreampMillibels() {
    return preampMillibels;
  }

  /** Returns a copy of the equalizer band levels, in millibels. Empty if no bands. */
  public short[] getEqualizerLevelsMillibels() {
    return levels.clone();
  }

  /**
   * Returns whether the output should be limited (e.g. a peak limiter).
   *
   * @param channelCount The number of channels.
   */
  public boolean shouldLimitOutput(int channelCount) {
    return hasBands();
  }

  /**
   * Returns whether center-channel gain is configured and available for the given channel count.
   *
   * @param channelCount The number of channels.
   */
  public boolean hasCenterGain(int channelCount) {
    return centerGainMillibels != 0 && isCenterGainAvailable(channelCount);
  }

  /** Returns the linear center-channel gain factor. */
  public float getCenterGainFactor() {
    return 1f + centerGainMillibels / 10000f;
  }

  /** Returns the compressor stability amount, in the range [0, 1]. */
  public float getStability() {
    return stability;
  }

  /**
   * Returns whether center-channel gain is available for the given channel count.
   *
   * @param channelCount The number of channels.
   */
  public static boolean isCenterGainAvailable(int channelCount) {
    return channelCount >= 3;
  }

  /** Builds {@link AudioEffectParameters} instances. */
  public static final class Builder {
    private int[] frequencies = new int[0];
    private short[] levels = new short[0];
    private float stability;
    private int boostMillibels;
    private int preampMillibels;
    private int centerGainMillibels;
    private float balance;
    private int channelMode = AudioChannelMode.AUTO;
    private boolean loudnessEnabled;

    /**
     * Sets the equalizer bands.
     *
     * @param frequencies The band center frequencies, in Hz.
     * @param levels The band levels, in millibels.
     * @return This builder.
     */
    public Builder setEqualizerBands(int[] frequencies, short[] levels) {
      this.frequencies = frequencies == null ? new int[0] : frequencies.clone();
      this.levels = levels == null ? new short[0] : levels.clone();
      return this;
    }

    /**
     * Sets the compressor stability amount.
     *
     * @param stability The amount in [0, 1].
     * @return This builder.
     */
    public Builder setStability(float stability) {
      this.stability = stability;
      return this;
    }

    /**
     * Sets the boost gain.
     *
     * @param boostMillibels The gain in millibels.
     * @return This builder.
     */
    public Builder setBoostMillibels(int boostMillibels) {
      this.boostMillibels = boostMillibels;
      return this;
    }

    /**
     * Sets the preamp gain.
     *
     * @param preampMillibels The gain in millibels.
     * @return This builder.
     */
    public Builder setPreampMillibels(int preampMillibels) {
      this.preampMillibels = preampMillibels;
      return this;
    }

    /**
     * Sets the center-channel gain.
     *
     * @param centerGainMillibels The gain in millibels.
     * @return This builder.
     */
    public Builder setCenterGainMillibels(int centerGainMillibels) {
      this.centerGainMillibels = centerGainMillibels;
      return this;
    }

    /**
     * Sets the left/right balance.
     *
     * @param balance The balance in [-1, 1].
     * @return This builder.
     */
    public Builder setBalance(float balance) {
      this.balance = balance;
      return this;
    }

    /**
     * Sets the channel remix mode.
     *
     * @param channelMode One of the {@link AudioChannelMode} constants.
     * @return This builder.
     */
    public Builder setChannelMode(int channelMode) {
      this.channelMode = channelMode;
      return this;
    }

    /**
     * Sets whether loudness normalization is enabled.
     *
     * @param enabled Whether loudness normalization is enabled.
     * @return This builder.
     */
    public Builder setLoudnessEnabled(boolean enabled) {
      this.loudnessEnabled = enabled;
      return this;
    }

    /** Builds the parameters. */
    public AudioEffectParameters build() {
      return new AudioEffectParameters(this);
    }
  }
}
