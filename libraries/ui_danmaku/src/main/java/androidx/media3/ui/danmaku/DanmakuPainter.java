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
package androidx.media3.ui.danmaku;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import java.util.List;

/** Owns paint configuration, text measurement, and rendering of active items. */
final class DanmakuPainter {

  private static final float DARK_LUMINANCE_THRESHOLD = 0.5f;
  private static final float COLORFUL_SATURATION = 0.55f;
  private static final float COLORFUL_VALUE = 1f;
  private static final float GRADIENT_HUE_OFFSET = 117f;
  private static final int[] GENERATED_GRADIENT = new int[0];
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix gradientMatrix = new Matrix();
  private final float[] hsv = new float[3];
  private final PaintColorState fillState = new PaintColorState();
  private final PaintColorState strokeState = new PaintColorState();
  private final ShadowPaintState shadowState = new ShadowPaintState();
  private final DisplayMetrics displayMetrics;
  private DanmakuConfig config = DanmakuConfig.DEFAULT;
  private float textSizePx;
  private float trackHeight;
  private float currentPaintSizePx;
  private float lastStrokeWidth = -1f;

  DanmakuPainter(DisplayMetrics displayMetrics) {
    this.displayMetrics = displayMetrics;
    fillPaint.setStyle(Paint.Style.FILL);
    strokePaint.setStyle(Paint.Style.STROKE);
    setConfig(DanmakuConfig.DEFAULT);
  }

  private static int computeShadowColor(int textColor) {
    float red = Color.red(textColor) / 255f;
    float green = Color.green(textColor) / 255f;
    float blue = Color.blue(textColor) / 255f;
    float luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
    return luminance < DARK_LUMINANCE_THRESHOLD ? Color.WHITE : Color.BLACK;
  }

  private static int applyAlpha(int color, float alpha) {
    int clampedAlpha = (int) (255 * Util.constrainValue(alpha, 0f, 1f));
    return (color & 0x00FFFFFF) | (clampedAlpha << 24);
  }

  private static float hueOf(int textHash) {
    return (Math.abs(textHash) * 137.508f) % 360f;
  }

  void setConfig(DanmakuConfig config) {
    if (this.config.styleMode == DanmakuConfig.STYLE_SHADOW
        && config.styleMode != DanmakuConfig.STYLE_SHADOW) {
      shadowState.clearIfSet(fillPaint);
    }
    this.config = config;
    textSizePx = resolveTextSizePx(config.textSizeSp);
    float strokeWidthPx = textSizePx * config.strokeWidthMultiplier;
    trackHeight = textSizePx * config.lineSpacing;
    Typeface typeface = config.typeface != null ? config.typeface : Typeface.DEFAULT;
    fillPaint.setTextSize(textSizePx);
    fillPaint.setFakeBoldText(config.textBold);
    fillPaint.setTypeface(typeface);
    strokePaint.setTextSize(textSizePx);
    strokePaint.setStrokeWidth(strokeWidthPx);
    strokePaint.setFakeBoldText(config.textBold);
    strokePaint.setTypeface(typeface);
    currentPaintSizePx = textSizePx;
    shadowState.invalidate();
    lastStrokeWidth = strokeWidthPx;
    fillState.reset();
    strokeState.reset();
  }

  float textSizePx() {
    return textSizePx;
  }

  float trackHeight() {
    return trackHeight;
  }

  float resolveTextSizePx(float textSizeSp) {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, displayMetrics)
        * config.textScale;
  }

  float measureWidth(DanmakuRenderItem item) {
    float sizePx = item.textSizeSp > 0 ? resolveTextSizePx(item.textSizeSp) : textSizePx;
    fillPaint.setTextSize(sizePx);
    float width = fillPaint.measureText(item.text);
    fillPaint.setTextSize(textSizePx);
    return width;
  }

  void remeasure(List<DanmakuRenderItem> items) {
    for (int i = 0; i < items.size(); i++) {
      DanmakuRenderItem item = items.get(i);
      float sizePx = item.textSizeSp > 0 ? resolveTextSizePx(item.textSizeSp) : textSizePx;
      if (fillPaint.getTextSize() != sizePx) {
        fillPaint.setTextSize(sizePx);
      }
      item.measuredWidth = fillPaint.measureText(item.text);
    }
    fillPaint.setTextSize(textSizePx);
  }

  DanmakuMeasurement measurement(int poolGeneration, int configGeneration) {
    return new DanmakuMeasurement(
        poolGeneration,
        configGeneration,
        textSizePx,
        config.textScale,
        displayMetrics,
        config.typeface != null ? config.typeface : Typeface.DEFAULT,
        config.textBold);
  }

  void beginFrame() {
    currentPaintSizePx = textSizePx;
    fillPaint.setTextSize(textSizePx);
    strokePaint.setTextSize(textSizePx);
  }

  boolean draw(
      @Nullable Canvas canvas,
      DanmakuRenderItem item,
      long currentMs,
      int viewWidth,
      int viewHeight) {
    long elapsedMs = currentMs - item.activatedTimeMs;
    float sizePx = item.textSizeSp > 0 ? resolveTextSizePx(item.textSizeSp) : textSizePx;
    setPaintSize(sizePx);
    float x;
    float y;
    boolean alive;
    switch (item.type) {
      case Danmaku.TYPE_SCROLL:
        float speed = (viewWidth + item.measuredWidth) / (float) effectiveScrollDurationMs();
        x = viewWidth - speed * elapsedMs;
        y = baseline(item.trackIndex, item.trackSpan);
        alive = x + item.measuredWidth > 0;
        break;
      case Danmaku.TYPE_REVERSE:
        float reverseSpeed = (viewWidth + item.measuredWidth) / (float) effectiveScrollDurationMs();
        x = -item.measuredWidth + reverseSpeed * elapsedMs;
        y = baseline(item.trackIndex, item.trackSpan);
        alive = x < viewWidth;
        break;
      case Danmaku.TYPE_TOP:
        x = (viewWidth - item.measuredWidth) / 2f;
        y = baseline(item.trackIndex, item.trackSpan);
        alive = elapsedMs < config.fixedDurationMs;
        break;
      case Danmaku.TYPE_BOTTOM:
        x = (viewWidth - item.measuredWidth) / 2f;
        y = bottomBaseline(item.trackIndex, item.trackSpan, viewHeight);
        alive = elapsedMs < config.fixedDurationMs;
        break;
      case Danmaku.TYPE_POSITIONED:
        long durationMs = item.durationMs > 0 ? item.durationMs : config.fixedDurationMs;
        x = item.x * viewWidth - item.measuredWidth / 2f;
        y = item.y * viewHeight;
        alive = elapsedMs < durationMs;
        break;
      default:
        return false;
    }
    if (!alive) {
      return false;
    }
    if (canvas != null) {
      drawAt(canvas, item, x, y, sizePx);
    }
    return true;
  }

  private void drawAt(Canvas canvas, DanmakuRenderItem item, float x, float y, float sizePx) {
    boolean forceWhite = shouldForceWhite(item);
    int color = forceWhite ? Color.WHITE : resolveColor(item);
    int alpha = (int) (Color.alpha(color) * (1f - config.transparency));
    fillState.apply(fillPaint, color, alpha);
    @Nullable int[] gradientColors = null;
    if (!forceWhite && config.colorMode == DanmakuConfig.COLOR_MODE_GRADIENT) {
      gradientColors = GENERATED_GRADIENT;
    } else if (!forceWhite
        && config.colorMode == DanmakuConfig.COLOR_MODE_DEFAULT
        && item.sourceGradientColors != null
        && item.sourceGradientColors.length >= 2) {
      gradientColors = item.sourceGradientColors;
    }
    if (gradientColors != null) {
      buildGradient(item, config.colorMode, gradientColors);
      gradientMatrix.setTranslate(x, 0);
      item.gradientShader.setLocalMatrix(gradientMatrix);
      fillPaint.setShader(item.gradientShader);
    }
    drawText(canvas, item.text, x, y, color, alpha, sizePx);
    if (gradientColors != null) {
      fillPaint.setShader(null);
    }
  }

  private int resolveColor(DanmakuRenderItem item) {
    if (config.colorMode != DanmakuConfig.COLOR_MODE_COLORFUL) {
      return item.color;
    }
    if (!item.generatedColorResolved) {
      item.generatedColor = hsvColor(hueOf(item.textHash));
      item.generatedColorResolved = true;
    }
    return item.generatedColor;
  }

  private boolean shouldForceWhite(DanmakuRenderItem item) {
    return (config.colorMode == DanmakuConfig.COLOR_MODE_COLORFUL
            || config.colorMode == DanmakuConfig.COLOR_MODE_GRADIENT)
        && item.forceGeneratedWhite;
  }

  private void buildGradient(DanmakuRenderItem item, int colorMode, int[] sourceColors) {
    if (item.gradientShader != null
        && item.gradientShaderWidth == item.measuredWidth
        && item.gradientShaderColorMode == colorMode) {
      return;
    }
    if (sourceColors.length == 0) {
      float hue = hueOf(item.textHash);
      item.gradientShader =
          new LinearGradient(
              0,
              0,
              item.measuredWidth,
              0,
              hsvColor(hue),
              hsvColor(hue + GRADIENT_HUE_OFFSET),
              Shader.TileMode.CLAMP);
    } else if (sourceColors.length == 2) {
      item.gradientShader =
          new LinearGradient(
              0, 0, item.measuredWidth, 0, sourceColors[0], sourceColors[1], Shader.TileMode.CLAMP);
    } else {
      float[] positions = new float[sourceColors.length];
      for (int i = 0; i < sourceColors.length; i++) {
        positions[i] = i / (float) (sourceColors.length - 1);
      }
      item.gradientShader =
          new LinearGradient(
              0, 0, item.measuredWidth, 0, sourceColors, positions, Shader.TileMode.CLAMP);
    }
    item.gradientShaderWidth = item.measuredWidth;
    item.gradientShaderColorMode = colorMode;
  }

  private void drawText(
      Canvas canvas, String text, float x, float y, int textColor, int alpha, float sizePx) {
    switch (config.styleMode) {
      case DanmakuConfig.STYLE_NONE:
        canvas.drawText(text, x, y, fillPaint);
        return;
      case DanmakuConfig.STYLE_SHADOW:
        int shadowColor = applyAlpha(computeShadowColor(textColor), 1f - config.shadowTransparency);
        shadowState.apply(fillPaint, shadowColor, sizePx * config.shadowRadiusMultiplier);
        canvas.drawText(text, x, y, fillPaint);
        return;
      case DanmakuConfig.STYLE_PROJECTION:
        drawProjection(canvas, text, x, y, textColor, sizePx);
        return;
      case DanmakuConfig.STYLE_STROKE:
      default:
        float strokeWidth = sizePx * config.strokeWidthMultiplier;
        if (lastStrokeWidth != strokeWidth) {
          strokePaint.setStrokeWidth(strokeWidth);
          lastStrokeWidth = strokeWidth;
        }
        strokeState.apply(strokePaint, computeShadowColor(textColor), alpha);
        canvas.drawText(text, x, y, strokePaint);
        canvas.drawText(text, x, y, fillPaint);
    }
  }

  private void drawProjection(
      Canvas canvas, String text, float x, float y, int textColor, float sizePx) {
    strokePaint.setStyle(Paint.Style.FILL);
    int projectionAlpha =
        (int)
            (255
                * Util.constrainValue(
                    (1f - config.projectionTransparency) * (1f - config.transparency), 0f, 1f));
    strokeState.apply(strokePaint, computeShadowColor(textColor), projectionAlpha);
    canvas.drawText(
        text,
        x + sizePx * config.projectionOffsetXMultiplier,
        y + sizePx * config.projectionOffsetYMultiplier,
        strokePaint);
    canvas.drawText(text, x, y, fillPaint);
    strokePaint.setStyle(Paint.Style.STROKE);
  }

  private void setPaintSize(float sizePx) {
    if (currentPaintSizePx != sizePx) {
      fillPaint.setTextSize(sizePx);
      strokePaint.setTextSize(sizePx);
      currentPaintSizePx = sizePx;
    }
  }

  private float baseline(int trackIndex, int trackSpan) {
    float trackTop = trackIndex * trackHeight;
    return trackTop + (trackSpan * trackHeight - fillPaint.descent() - fillPaint.ascent()) / 2f;
  }

  private float bottomBaseline(int trackIndex, int trackSpan, int viewHeight) {
    float trackTop = viewHeight - (trackIndex + trackSpan) * trackHeight;
    return trackTop + (trackSpan * trackHeight - fillPaint.descent() - fillPaint.ascent()) / 2f;
  }

  private long effectiveScrollDurationMs() {
    return Math.max(1L, (long) (config.durationMs / Math.max(0.01f, config.scrollSpeedFactor)));
  }

  private int hsvColor(float hue) {
    hsv[0] = ((hue % 360f) + 360f) % 360f;
    hsv[1] = COLORFUL_SATURATION;
    hsv[2] = COLORFUL_VALUE;
    return Color.HSVToColor(hsv);
  }
}
