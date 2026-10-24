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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * Describes a 2D transform applied to the rendered video surface: independent horizontal/vertical
 * scale and pan offsets.
 */
@UnstableApi
public final class VideoTransform {

  /** The maximum allowed scale factor. */
  public static final float MAX_SCALE = 8.0f;

  /** The identity transform: no scaling, no panning. */
  public static final VideoTransform DEFAULT = new VideoTransform(1.0f, 1.0f, 0.0f, 0.0f);

  /** Horizontal scale factor (1.0 = no scaling). */
  public final float scaleX;

  /** Vertical scale factor (1.0 = no scaling). */
  public final float scaleY;

  /** Horizontal pan offset, in normalized screen units. */
  public final float panX;

  /** Vertical pan offset, in normalized screen units. */
  public final float panY;

  /** Creates a new transform. */
  public VideoTransform(float scaleX, float scaleY, float panX, float panY) {
    this.scaleX = scaleX;
    this.scaleY = scaleY;
    this.panX = panX;
    this.panY = panY;
  }
}
