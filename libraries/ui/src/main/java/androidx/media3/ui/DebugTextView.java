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
package androidx.media3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.view.Gravity;
import android.widget.TextView;
import androidx.annotation.VisibleForTesting;

/** A text view that renders a solid outline instead of a blurred zero-offset shadow. */
final class DebugTextView extends TextView {

  private float outlineWidth;
  private int outlineColor;

  DebugTextView(Context context) {
    super(context);
    setGravity(Gravity.START | Gravity.TOP);
  }

  @Override
  public void setShadowLayer(float radius, float dx, float dy, int color) {
    if (dx != 0.0f || dy != 0.0f) {
      outlineWidth = 0.0f;
      super.setShadowLayer(radius, dx, dy, color);
      return;
    }
    outlineWidth = Math.max(0.0f, radius);
    outlineColor = color;
    super.setShadowLayer(0.0f, 0.0f, 0.0f, Color.TRANSPARENT);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    Layout layout = getLayout();
    if (layout != null && hasOutline()) {
      drawOutline(canvas, layout);
    }
    super.onDraw(canvas);
  }

  private boolean hasOutline() {
    return outlineWidth > 0.0f && Color.alpha(outlineColor) != 0;
  }

  private void drawOutline(Canvas canvas, Layout layout) {
    TextPaint paint = getPaint();
    int originalColor = paint.getColor();
    Paint.Style originalStyle = paint.getStyle();
    float originalStrokeWidth = paint.getStrokeWidth();
    Paint.Join originalStrokeJoin = paint.getStrokeJoin();
    int saveCount = canvas.save();
    canvas.translate(
        getCompoundPaddingLeft() - getScrollX(), getExtendedPaddingTop() - getScrollY());
    paint.setColor(outlineColor);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(outlineWidth * 2.0f);
    paint.setStrokeJoin(Paint.Join.ROUND);
    layout.draw(canvas);
    canvas.restoreToCount(saveCount);
    paint.setColor(originalColor);
    paint.setStyle(originalStyle);
    paint.setStrokeWidth(originalStrokeWidth);
    paint.setStrokeJoin(originalStrokeJoin);
  }

  @VisibleForTesting
  /* package */ float getOutlineWidth() {
    return outlineWidth;
  }

  @VisibleForTesting
  /* package */ int getOutlineColor() {
    return outlineColor;
  }
}
