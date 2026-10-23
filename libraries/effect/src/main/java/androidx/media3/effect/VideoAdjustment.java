/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.effect;

import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Immutable collection of color, tone and detail tuning parameters applied to video frames by
 * {@link ColorToneAdjustEffect} and {@link DetailAdjustEffect}.
 *
 * <p>This is a plain data holder. The effect classes store the adjustment and expose it to the
 * frame-processing pipeline; the concrete shader wiring is owned by the caller.
 */
@UnstableApi
public final class VideoAdjustment {

  /** A builder for {@link VideoAdjustment} instances. */
  public static final class Builder {
    private float saturation;
    private float contrast;
    private float brightness;
    private float gamma;
    private float hue;
    private float redGain;
    private float blueGain;
    private float sharpness;
    private float threshold;
    private float shadowLift;

    /** Creates a new builder with default (neutral) values. */
    public Builder() {
      saturation = 1.0f;
      contrast = 1.0f;
      brightness = 0.0f;
      gamma = 1.0f;
      hue = 0.0f;
      redGain = 1.0f;
      blueGain = 1.0f;
      sharpness = 0.0f;
      threshold = 0.03f;
      shadowLift = 0.0f;
    }

    /** Sets the saturation multiplier (default {@code 1.0}). */
    @CanIgnoreReturnValue
    public Builder setSaturation(float saturation) {
      this.saturation = saturation;
      return this;
    }

    /** Sets the contrast multiplier (default {@code 1.0}). */
    @CanIgnoreReturnValue
    public Builder setContrast(float contrast) {
      this.contrast = contrast;
      return this;
    }

    /** Sets the brightness offset (default {@code 0.0}). */
    @CanIgnoreReturnValue
    public Builder setBrightness(float brightness) {
      this.brightness = brightness;
      return this;
    }

    /** Sets the gamma correction value (default {@code 1.0}). */
    @CanIgnoreReturnValue
    public Builder setGamma(float gamma) {
      this.gamma = gamma;
      return this;
    }

    /** Sets the hue rotation offset in degrees (default {@code 0.0}). */
    @CanIgnoreReturnValue
    public Builder setHue(float hue) {
      this.hue = hue;
      return this;
    }

    /** Sets the red channel gain multiplier (default {@code 1.0}). */
    @CanIgnoreReturnValue
    public Builder setRedGain(float redGain) {
      this.redGain = redGain;
      return this;
    }

    /** Sets the blue channel gain multiplier (default {@code 1.0}). */
    @CanIgnoreReturnValue
    public Builder setBlueGain(float blueGain) {
      this.blueGain = blueGain;
      return this;
    }

    /** Sets the sharpness amount (default {@code 0.0}). */
    @CanIgnoreReturnValue
    public Builder setSharpness(float sharpness) {
      this.sharpness = sharpness;
      return this;
    }

    /** Sets the detail/sharpen threshold (default {@code 0.03}). */
    @CanIgnoreReturnValue
    public Builder setThreshold(float threshold) {
      this.threshold = threshold;
      return this;
    }

    /** Sets the shadow lift amount (default {@code 0.0}). */
    @CanIgnoreReturnValue
    public Builder setShadowLift(float shadowLift) {
      this.shadowLift = shadowLift;
      return this;
    }

    /** Creates a new {@link VideoAdjustment} from the current builder values. */
    public VideoAdjustment build() {
      return new VideoAdjustment(
          saturation,
          contrast,
          brightness,
          gamma,
          hue,
          redGain,
          blueGain,
          sharpness,
          threshold,
          shadowLift);
    }
  }

  private final float saturation;
  private final float contrast;
  private final float brightness;
  private final float gamma;
  private final float hue;
  private final float redGain;
  private final float blueGain;
  private final float sharpness;
  private final float threshold;
  private final float shadowLift;

  private VideoAdjustment(
      float saturation,
      float contrast,
      float brightness,
      float gamma,
      float hue,
      float redGain,
      float blueGain,
      float sharpness,
      float threshold,
      float shadowLift) {
    this.saturation = saturation;
    this.contrast = contrast;
    this.brightness = brightness;
    this.gamma = gamma;
    this.hue = hue;
    this.redGain = redGain;
    this.blueGain = blueGain;
    this.sharpness = sharpness;
    this.threshold = threshold;
    this.shadowLift = shadowLift;
  }

  /** Returns the saturation multiplier. */
  public float getSaturation() {
    return saturation;
  }

  /** Returns the contrast multiplier. */
  public float getContrast() {
    return contrast;
  }

  /** Returns the brightness offset. */
  public float getBrightness() {
    return brightness;
  }

  /** Returns the gamma correction value. */
  public float getGamma() {
    return gamma;
  }

  /** Returns the hue rotation offset in degrees. */
  public float getHue() {
    return hue;
  }

  /** Returns the red channel gain multiplier. */
  public float getRedGain() {
    return redGain;
  }

  /** Returns the blue channel gain multiplier. */
  public float getBlueGain() {
    return blueGain;
  }

  /** Returns the sharpness amount. */
  public float getSharpness() {
    return sharpness;
  }

  /** Returns the detail/sharpen threshold. */
  public float getThreshold() {
    return threshold;
  }

  /** Returns the shadow lift amount. */
  public float getShadowLift() {
    return shadowLift;
  }
}
