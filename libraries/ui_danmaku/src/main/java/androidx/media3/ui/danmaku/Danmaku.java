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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Color;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Comparator;

/** An immutable danmaku item. */
@UnstableApi
public final class Danmaku {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({TYPE_SCROLL, TYPE_TOP, TYPE_BOTTOM, TYPE_REVERSE, TYPE_POSITIONED})
  /** Danmaku display type. */
  public @interface Type {}

  /** Orders items by {@link #timeMs}. */
  public static final Comparator<Danmaku> BY_TIME = (a, b) -> Long.compare(a.timeMs, b.timeMs);

  /** A right-to-left scrolling item. */
  public static final int TYPE_SCROLL = 1;

  /** A fixed item displayed at the bottom. */
  public static final int TYPE_BOTTOM = 4;

  /** A fixed item displayed at the top. */
  public static final int TYPE_TOP = 5;

  /** A left-to-right scrolling item. */
  public static final int TYPE_REVERSE = 6;

  /** An item displayed at normalized coordinates. */
  public static final int TYPE_POSITIONED = 7;

  /** The default item pool. */
  public static final int POOL_NORMAL = 0;

  /** The subtitle item pool. */
  public static final int POOL_SUBTITLE = 1;

  /** The special item pool. */
  public static final int POOL_SPECIAL = 2;

  /** The displayed text. */
  public final String text;

  /** The presentation time in milliseconds. */
  public final long timeMs;

  /** The {@link Type}. */
  public final @Type int type;

  /** The ARGB text color. */
  public final @ColorInt int color;

  /** The item-specific text size in sp, or zero to use the configured default. */
  public final float textSizeSp;

  /** The item pool. */
  public final int pool;

  /** The source user identifier, or an empty string if unavailable. */
  public final String userId;

  /** The source row identifier, or zero if unavailable. */
  public final long rowId;

  /** The normalized horizontal position for positioned items. */
  public final float x;

  /** The normalized vertical position for positioned items. */
  public final float y;

  /**
   * The display duration for positioned items in milliseconds, or zero to use the configured
   * default.
   */
  public final long durationMs;

  @Nullable private final int[] sourceGradientColors;

  /** Creates a danmaku item. */
  public Danmaku(
      String text,
      long timeMs,
      @Type int type,
      @ColorInt int color,
      float textSizeSp,
      int pool,
      String userId,
      long rowId) {
    this(text, timeMs, type, color, textSizeSp, pool, userId, rowId, null);
  }

  /** Creates a danmaku item with optional source gradient colors. */
  public Danmaku(
      String text,
      long timeMs,
      @Type int type,
      @ColorInt int color,
      float textSizeSp,
      int pool,
      String userId,
      long rowId,
      @Nullable int[] sourceGradientColors) {
    this.text = checkNotNull(text);
    this.timeMs = timeMs;
    this.type = type;
    this.color = color;
    this.textSizeSp = textSizeSp;
    this.pool = pool;
    this.userId = checkNotNull(userId);
    this.rowId = rowId;
    this.x = 0f;
    this.y = 0f;
    this.durationMs = 0;
    this.sourceGradientColors = sourceGradientColors != null ? sourceGradientColors.clone() : null;
  }

  /** Creates a positioned danmaku item. */
  public Danmaku(
      String text,
      long timeMs,
      float x,
      float y,
      @ColorInt int color,
      float textSizeSp,
      long durationMs,
      int pool,
      String userId,
      long rowId) {
    this.text = checkNotNull(text);
    this.timeMs = timeMs;
    this.type = TYPE_POSITIONED;
    this.color = color;
    this.textSizeSp = textSizeSp;
    this.pool = pool;
    this.userId = checkNotNull(userId);
    this.rowId = rowId;
    this.x = x;
    this.y = y;
    this.durationMs = durationMs;
    this.sourceGradientColors = null;
  }

  /** Creates a normal-pool danmaku item. */
  public Danmaku(String text, long timeMs, @Type int type, @ColorInt int color, float textSizeSp) {
    this(text, timeMs, type, color, textSizeSp, POOL_NORMAL, "", 0);
  }

  /** Creates a normal-pool danmaku item with optional source gradient colors. */
  public Danmaku(
      String text,
      long timeMs,
      @Type int type,
      @ColorInt int color,
      float textSizeSp,
      @Nullable int[] sourceGradientColors) {
    this(text, timeMs, type, color, textSizeSp, POOL_NORMAL, "", 0, sourceGradientColors);
  }

  /** Creates a normal-pool danmaku item that uses the configured text size. */
  public Danmaku(String text, long timeMs, @Type int type, @ColorInt int color) {
    this(text, timeMs, type, color, 0);
  }

  /** Creates a white right-to-left scrolling item. */
  public Danmaku(String text, long timeMs) {
    this(text, timeMs, TYPE_SCROLL, Color.WHITE, 0);
  }

  /** Returns a copy of the source gradient colors, or {@code null} if none were specified. */
  @Nullable
  public int[] getSourceGradientColors() {
    return sourceGradientColors != null ? sourceGradientColors.clone() : null;
  }
}
