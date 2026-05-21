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
 *
 */
package androidx.media3.ui.danmaku;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Typeface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Immutable rendering and layout configuration for a {@link DanmakuView}. */
@UnstableApi
public final class DanmakuConfig {

  /** Text edge style. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({STYLE_NONE, STYLE_SHADOW, STYLE_STROKE, STYLE_PROJECTION})
  public @interface StyleMode {}

  /** Text color mode. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({COLOR_MODE_DEFAULT, COLOR_MODE_COLORFUL, COLOR_MODE_GRADIENT})
  public @interface ColorMode {}

  /** Draws text without an edge effect. */
  public static final int STYLE_NONE = 0;

  /** Draws text with a shadow. */
  public static final int STYLE_SHADOW = 1;

  /** Draws text with a stroke. */
  public static final int STYLE_STROKE = 2;

  /** Draws text with a projected edge. */
  public static final int STYLE_PROJECTION = 3;

  /** Uses source text colors. */
  public static final int COLOR_MODE_DEFAULT = 1;

  /** Generates one color for each item. */
  public static final int COLOR_MODE_COLORFUL = 2;

  /** Generates a gradient for each item. */
  public static final int COLOR_MODE_GRADIENT = 3;

  /** Default configuration. */
  public static final DanmakuConfig DEFAULT = new Builder().build();

  private static final float MIN_TEXT_SIZE_SP = 0.01f;

  /** Scrolling item duration in milliseconds. */
  public final long durationMs;

  /** Fixed item duration in milliseconds. */
  public final long fixedDurationMs;

  /** Default text size in sp. */
  public final float textSizeSp;

  /** Text-size scale factor. */
  public final float textScale;

  /** Text typeface, or {@code null} to use the platform default. */
  public final @Nullable Typeface typeface;

  /** Whether text is bold. */
  public final boolean textBold;

  /** Text transparency in the range zero to one. */
  public final float transparency;

  /** Text edge style. */
  public final @StyleMode int styleMode;

  /** Shadow transparency in the range zero to one. */
  public final float shadowTransparency;

  /** Shadow radius relative to text size. */
  public final float shadowRadiusMultiplier;

  /** Stroke width relative to text size. */
  public final float strokeWidthMultiplier;

  /** Horizontal projection offset relative to text size. */
  public final float projectionOffsetXMultiplier;

  /** Vertical projection offset relative to text size. */
  public final float projectionOffsetYMultiplier;

  /** Projection transparency in the range zero to one. */
  public final float projectionTransparency;

  /** Maximum number of simultaneously active items. */
  public final int maxOnScreen;

  /** Maximum number of scrolling tracks, or zero for automatic sizing. */
  public final int maxScrollLines;

  /** Maximum number of top tracks, or zero for automatic sizing. */
  public final int maxTopLines;

  /** Maximum number of bottom tracks, or zero for automatic sizing. */
  public final int maxBottomLines;

  /** Fraction of the view height available to scrolling items. */
  public final float scrollAreaRatio;

  /** Track height relative to the default text size. */
  public final float lineSpacing;

  /** Whether right-to-left scrolling items are shown. */
  public final boolean showScroll;

  /** Whether top items are shown. */
  public final boolean showTop;

  /** Whether bottom items are shown. */
  public final boolean showBottom;

  /** Whether left-to-right scrolling items are shown. */
  public final boolean showReverse;

  /** Whether right-to-left scrolling items may overlap. */
  public final boolean allowScrollOverlap;

  /** Whether top items may overlap. */
  public final boolean allowTopOverlap;

  /** Whether bottom items may overlap. */
  public final boolean allowBottomOverlap;

  /** Whether left-to-right scrolling items may overlap. */
  public final boolean allowReverseOverlap;

  /** Scrolling speed factor. */
  public final float scrollSpeedFactor;

  /** Minimum scrolling gap relative to the default text size. */
  public final float scrollGapRatio;

  /** Whether positioned items are shown. */
  public final boolean showPositioned;

  /** Whether subtitle-pool items are shown. */
  public final boolean showSubtitle;

  /** Whether special-pool items are shown. */
  public final boolean showSpecial;

  /** Text color mode. */
  public final @ColorMode int colorMode;

  /** Offset applied to source item times in milliseconds. */
  public final long timeOffsetMs;

  private DanmakuConfig(Builder builder) {
    this.durationMs = builder.durationMs;
    this.fixedDurationMs = builder.fixedDurationMs;
    this.textSizeSp = builder.textSizeSp;
    this.textScale = builder.textScale;
    this.typeface = builder.typeface;
    this.textBold = builder.textBold;
    this.transparency = builder.transparency;
    this.styleMode = builder.styleMode;
    this.shadowTransparency = builder.shadowTransparency;
    this.shadowRadiusMultiplier = builder.shadowRadiusMultiplier;
    this.strokeWidthMultiplier = builder.strokeWidthMultiplier;
    this.projectionOffsetXMultiplier = builder.projectionOffsetXMultiplier;
    this.projectionOffsetYMultiplier = builder.projectionOffsetYMultiplier;
    this.projectionTransparency = builder.projectionTransparency;
    this.maxOnScreen = builder.maxOnScreen;
    this.maxScrollLines = builder.maxScrollLines;
    this.maxTopLines = builder.maxTopLines;
    this.maxBottomLines = builder.maxBottomLines;
    this.scrollAreaRatio = builder.scrollAreaRatio;
    this.lineSpacing = builder.lineSpacing;
    this.showScroll = builder.showScroll;
    this.showTop = builder.showTop;
    this.showBottom = builder.showBottom;
    this.showReverse = builder.showReverse;
    this.allowScrollOverlap = builder.allowScrollOverlap;
    this.allowTopOverlap = builder.allowTopOverlap;
    this.allowBottomOverlap = builder.allowBottomOverlap;
    this.allowReverseOverlap = builder.allowReverseOverlap;
    this.scrollSpeedFactor = builder.scrollSpeedFactor;
    this.scrollGapRatio = builder.scrollGapRatio;
    this.showPositioned = builder.showPositioned;
    this.showSubtitle = builder.showSubtitle;
    this.showSpecial = builder.showSpecial;
    this.colorMode = builder.colorMode;
    this.timeOffsetMs = builder.timeOffsetMs;
  }

  /** Returns a builder initialized from this configuration. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /** Builds {@link DanmakuConfig} instances. */
  public static final class Builder {

    private long durationMs;
    private long fixedDurationMs;
    private float textSizeSp;
    private float textScale;
    private @Nullable Typeface typeface;
    private boolean textBold;
    private float transparency;
    private @StyleMode int styleMode;
    private float shadowTransparency;
    private float shadowRadiusMultiplier;
    private float strokeWidthMultiplier;
    private float projectionOffsetXMultiplier;
    private float projectionOffsetYMultiplier;
    private float projectionTransparency;
    private int maxOnScreen;
    private int maxScrollLines;
    private int maxTopLines;
    private int maxBottomLines;
    private float scrollAreaRatio;
    private float lineSpacing;
    private boolean showScroll;
    private boolean showTop;
    private boolean showBottom;
    private boolean showReverse;
    private boolean allowScrollOverlap;
    private boolean allowTopOverlap;
    private boolean allowBottomOverlap;
    private boolean allowReverseOverlap;
    private float scrollSpeedFactor;
    private float scrollGapRatio;
    private boolean showPositioned;
    private boolean showSubtitle;
    private boolean showSpecial;
    private @ColorMode int colorMode;
    private long timeOffsetMs;

    /** Creates a builder initialized with default values. */
    public Builder() {
      durationMs = 8000;
      fixedDurationMs = 5000;
      textSizeSp = 14f;
      textScale = 1f;
      typeface = null;
      textBold = false;
      transparency = 0f;
      styleMode = STYLE_STROKE;
      shadowTransparency = 0.1f;
      shadowRadiusMultiplier = 0.15f;
      strokeWidthMultiplier = 0.12f;
      projectionOffsetXMultiplier = 0.08f;
      projectionOffsetYMultiplier = 0.08f;
      projectionTransparency = 0.2f;
      maxOnScreen = 150;
      maxScrollLines = 0;
      maxTopLines = 0;
      maxBottomLines = 0;
      scrollAreaRatio = 0.5f;
      lineSpacing = 1.4f;
      showScroll = true;
      showTop = true;
      showBottom = true;
      showReverse = true;
      allowScrollOverlap = false;
      allowTopOverlap = false;
      allowBottomOverlap = false;
      allowReverseOverlap = false;
      scrollSpeedFactor = 1f;
      scrollGapRatio = 0f;
      showPositioned = true;
      showSubtitle = true;
      showSpecial = true;
      colorMode = COLOR_MODE_DEFAULT;
      timeOffsetMs = 0;
    }

    private Builder(DanmakuConfig config) {
      this.durationMs = config.durationMs;
      this.fixedDurationMs = config.fixedDurationMs;
      this.textSizeSp = config.textSizeSp;
      this.textScale = config.textScale;
      this.typeface = config.typeface;
      this.textBold = config.textBold;
      this.transparency = config.transparency;
      this.styleMode = config.styleMode;
      this.shadowTransparency = config.shadowTransparency;
      this.shadowRadiusMultiplier = config.shadowRadiusMultiplier;
      this.strokeWidthMultiplier = config.strokeWidthMultiplier;
      this.projectionOffsetXMultiplier = config.projectionOffsetXMultiplier;
      this.projectionOffsetYMultiplier = config.projectionOffsetYMultiplier;
      this.projectionTransparency = config.projectionTransparency;
      this.maxOnScreen = config.maxOnScreen;
      this.maxScrollLines = config.maxScrollLines;
      this.maxTopLines = config.maxTopLines;
      this.maxBottomLines = config.maxBottomLines;
      this.scrollAreaRatio = config.scrollAreaRatio;
      this.lineSpacing = config.lineSpacing;
      this.showScroll = config.showScroll;
      this.showTop = config.showTop;
      this.showBottom = config.showBottom;
      this.showReverse = config.showReverse;
      this.allowScrollOverlap = config.allowScrollOverlap;
      this.allowTopOverlap = config.allowTopOverlap;
      this.allowBottomOverlap = config.allowBottomOverlap;
      this.allowReverseOverlap = config.allowReverseOverlap;
      this.scrollSpeedFactor = config.scrollSpeedFactor;
      this.scrollGapRatio = config.scrollGapRatio;
      this.showPositioned = config.showPositioned;
      this.showSubtitle = config.showSubtitle;
      this.showSpecial = config.showSpecial;
      this.colorMode = config.colorMode;
      this.timeOffsetMs = config.timeOffsetMs;
    }

    /** Sets the scrolling item duration in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setDurationMs(long durationMs) {
      this.durationMs = Math.max(1L, durationMs);
      return this;
    }

    /** Sets the fixed item duration in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setFixedDurationMs(long fixedDurationMs) {
      this.fixedDurationMs = Math.max(1L, fixedDurationMs);
      return this;
    }

    /** Sets the default text size in sp. */
    @CanIgnoreReturnValue
    public Builder setTextSizeSp(float textSizeSp) {
      this.textSizeSp = Math.max(MIN_TEXT_SIZE_SP, textSizeSp);
      return this;
    }

    /** Sets the text-size scale factor. */
    @CanIgnoreReturnValue
    public Builder setTextScale(float textScale) {
      this.textScale = Math.max(0.01f, textScale);
      return this;
    }

    /** Sets the typeface, or clears it if {@code typeface} is {@code null}. */
    @CanIgnoreReturnValue
    public Builder setTypeface(@Nullable Typeface typeface) {
      this.typeface = typeface;
      return this;
    }

    /** Sets whether text is bold. */
    @CanIgnoreReturnValue
    public Builder setTextBold(boolean textBold) {
      this.textBold = textBold;
      return this;
    }

    /** Sets text transparency in the range zero to one. */
    @CanIgnoreReturnValue
    public Builder setTransparency(float transparency) {
      this.transparency = Util.constrainValue(transparency, 0f, 1f);
      return this;
    }

    /** Sets the text edge style. */
    @CanIgnoreReturnValue
    public Builder setStyleMode(@StyleMode int styleMode) {
      this.styleMode = styleMode;
      return this;
    }

    /** Sets shadow transparency in the range zero to one. */
    @CanIgnoreReturnValue
    public Builder setShadowTransparency(float shadowTransparency) {
      this.shadowTransparency = Util.constrainValue(shadowTransparency, 0f, 1f);
      return this;
    }

    /** Sets the shadow radius relative to text size. */
    @CanIgnoreReturnValue
    public Builder setShadowRadiusMultiplier(float shadowRadiusMultiplier) {
      this.shadowRadiusMultiplier = Util.constrainValue(shadowRadiusMultiplier, 0f, 1f);
      return this;
    }

    /** Sets the stroke width relative to text size. */
    @CanIgnoreReturnValue
    public Builder setStrokeWidthMultiplier(float strokeWidthMultiplier) {
      this.strokeWidthMultiplier = Util.constrainValue(strokeWidthMultiplier, 0f, 1f);
      return this;
    }

    /** Sets the horizontal projection offset relative to text size. */
    @CanIgnoreReturnValue
    public Builder setProjectionOffsetXMultiplier(float projectionOffsetXMultiplier) {
      this.projectionOffsetXMultiplier = Util.constrainValue(projectionOffsetXMultiplier, 0f, 1f);
      return this;
    }

    /** Sets the vertical projection offset relative to text size. */
    @CanIgnoreReturnValue
    public Builder setProjectionOffsetYMultiplier(float projectionOffsetYMultiplier) {
      this.projectionOffsetYMultiplier = Util.constrainValue(projectionOffsetYMultiplier, 0f, 1f);
      return this;
    }

    /** Sets projection transparency in the range zero to one. */
    @CanIgnoreReturnValue
    public Builder setProjectionTransparency(float projectionTransparency) {
      this.projectionTransparency = Util.constrainValue(projectionTransparency, 0f, 1f);
      return this;
    }

    /** Sets the maximum number of simultaneously active items. */
    @CanIgnoreReturnValue
    public Builder setMaxOnScreen(int maxOnScreen) {
      this.maxOnScreen = Math.max(1, maxOnScreen);
      return this;
    }

    /** Sets the maximum scrolling track count, or zero for automatic sizing. */
    @CanIgnoreReturnValue
    public Builder setMaxScrollLines(int maxScrollLines) {
      this.maxScrollLines = Math.max(0, maxScrollLines);
      return this;
    }

    /** Sets the maximum top track count, or zero for automatic sizing. */
    @CanIgnoreReturnValue
    public Builder setMaxTopLines(int maxTopLines) {
      this.maxTopLines = Math.max(0, maxTopLines);
      return this;
    }

    /** Sets the maximum bottom track count, or zero for automatic sizing. */
    @CanIgnoreReturnValue
    public Builder setMaxBottomLines(int maxBottomLines) {
      this.maxBottomLines = Math.max(0, maxBottomLines);
      return this;
    }

    /** Sets the fraction of view height available to scrolling items. */
    @CanIgnoreReturnValue
    public Builder setScrollAreaRatio(float scrollAreaRatio) {
      this.scrollAreaRatio = Util.constrainValue(scrollAreaRatio, 0.01f, 1f);
      return this;
    }

    /** Sets track height relative to the default text size. */
    @CanIgnoreReturnValue
    public Builder setLineSpacing(float lineSpacing) {
      this.lineSpacing = Math.max(0.01f, lineSpacing);
      return this;
    }

    /** Sets whether right-to-left scrolling items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowScroll(boolean showScroll) {
      this.showScroll = showScroll;
      return this;
    }

    /** Sets whether top items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowTop(boolean showTop) {
      this.showTop = showTop;
      return this;
    }

    /** Sets whether bottom items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowBottom(boolean showBottom) {
      this.showBottom = showBottom;
      return this;
    }

    /** Sets whether left-to-right scrolling items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowReverse(boolean showReverse) {
      this.showReverse = showReverse;
      return this;
    }

    /** Sets whether all item types may overlap. */
    @CanIgnoreReturnValue
    public Builder setAllowOverlapping(boolean allowOverlapping) {
      this.allowScrollOverlap = allowOverlapping;
      this.allowTopOverlap = allowOverlapping;
      this.allowBottomOverlap = allowOverlapping;
      this.allowReverseOverlap = allowOverlapping;
      return this;
    }

    /** Sets whether right-to-left scrolling items may overlap. */
    @CanIgnoreReturnValue
    public Builder setAllowScrollOverlap(boolean allowScrollOverlap) {
      this.allowScrollOverlap = allowScrollOverlap;
      return this;
    }

    /** Sets whether top items may overlap. */
    @CanIgnoreReturnValue
    public Builder setAllowTopOverlap(boolean allowTopOverlap) {
      this.allowTopOverlap = allowTopOverlap;
      return this;
    }

    /** Sets whether bottom items may overlap. */
    @CanIgnoreReturnValue
    public Builder setAllowBottomOverlap(boolean allowBottomOverlap) {
      this.allowBottomOverlap = allowBottomOverlap;
      return this;
    }

    /** Sets whether left-to-right scrolling items may overlap. */
    @CanIgnoreReturnValue
    public Builder setAllowReverseOverlap(boolean allowReverseOverlap) {
      this.allowReverseOverlap = allowReverseOverlap;
      return this;
    }

    /** Sets the scrolling speed factor. */
    @CanIgnoreReturnValue
    public Builder setScrollSpeedFactor(float scrollSpeedFactor) {
      this.scrollSpeedFactor = Math.max(0.01f, scrollSpeedFactor);
      return this;
    }

    /** Sets the minimum scrolling gap relative to the default text size. */
    @CanIgnoreReturnValue
    public Builder setScrollGapRatio(float scrollGapRatio) {
      this.scrollGapRatio = Math.min(5f, Math.max(0f, scrollGapRatio));
      return this;
    }

    /** Sets whether positioned items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowPositioned(boolean showPositioned) {
      this.showPositioned = showPositioned;
      return this;
    }

    /** Sets whether subtitle-pool items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowSubtitle(boolean showSubtitle) {
      this.showSubtitle = showSubtitle;
      return this;
    }

    /** Sets whether special-pool items are shown. */
    @CanIgnoreReturnValue
    public Builder setShowSpecial(boolean showSpecial) {
      this.showSpecial = showSpecial;
      return this;
    }

    /** Sets the text color mode. */
    @CanIgnoreReturnValue
    public Builder setColorMode(@ColorMode int colorMode) {
      this.colorMode = colorMode;
      return this;
    }

    /** Sets the offset applied to source item times in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setTimeOffsetMs(long timeOffsetMs) {
      this.timeOffsetMs = timeOffsetMs;
      return this;
    }

    /** Builds the configuration. */
    public DanmakuConfig build() {
      return new DanmakuConfig(this);
    }
  }
}
