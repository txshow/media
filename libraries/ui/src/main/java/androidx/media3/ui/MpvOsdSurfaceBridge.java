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

import android.graphics.PixelFormat;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Manages mpv's optional OSD surface without adding a dependency on the mpvplayer module. */
final class MpvOsdSurfaceBridge {

  private static final String TAG = "MpvOsdSurface";
  // LINT.IfChange
  private static final String SET_SURFACE_METHOD = "setOsdSurfaceView";
  private static final String CLEAR_SURFACE_METHOD = "clearOsdSurfaceView";
  // LINT.ThenChange(../../../../../../proguard-rules.txt)

  @Nullable private final FrameLayout contentFrame;
  @Nullable private Player player;
  @Nullable private Method clearSurfaceMethod;
  @Nullable private SurfaceView surfaceView;

  MpvOsdSurfaceBridge(@Nullable FrameLayout contentFrame) {
    this.contentFrame = contentFrame;
  }

  void setPlayer(@Nullable Player player) {
    if (this.player == player) {
      return;
    }
    clearPlayer();
    if (player == null) {
      removeSurfaceView();
      return;
    }
    try {
      Method setSurfaceMethod = player.getClass().getMethod(SET_SURFACE_METHOD, SurfaceView.class);
      Method clearSurfaceMethod =
          player.getClass().getMethod(CLEAR_SURFACE_METHOD, SurfaceView.class);
      @Nullable SurfaceView surfaceView = getOrCreateSurfaceView();
      if (surfaceView != null) {
        this.player = player;
        this.clearSurfaceMethod = clearSurfaceMethod;
        setSurfaceMethod.invoke(player, surfaceView);
      }
    } catch (NoSuchMethodException e) {
      removeSurfaceView();
    } catch (IllegalAccessException | InvocationTargetException e) {
      Log.w(TAG, "Unable to attach mpv OSD surface", e);
      clearPlayer();
      removeSurfaceView();
    }
  }

  private void clearPlayer() {
    @Nullable Player player = this.player;
    @Nullable Method clearSurfaceMethod = this.clearSurfaceMethod;
    @Nullable SurfaceView surfaceView = this.surfaceView;
    this.player = null;
    this.clearSurfaceMethod = null;
    if (player == null || clearSurfaceMethod == null || surfaceView == null) {
      return;
    }
    try {
      clearSurfaceMethod.invoke(player, surfaceView);
    } catch (IllegalAccessException | InvocationTargetException e) {
      Log.w(TAG, "Unable to detach mpv OSD surface", e);
    }
  }

  @Nullable
  private SurfaceView getOrCreateSurfaceView() {
    if (surfaceView != null || contentFrame == null) {
      return surfaceView;
    }
    SurfaceView surfaceView = new SurfaceView(contentFrame.getContext());
    surfaceView.setZOrderMediaOverlay(true);
    surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
    surfaceView.setClickable(false);
    surfaceView.setFocusable(false);
    // Share the video content frame so direct-rendered OSD coordinates follow the video geometry.
    contentFrame.addView(
        surfaceView,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    this.surfaceView = surfaceView;
    return surfaceView;
  }

  private void removeSurfaceView() {
    @Nullable SurfaceView surfaceView = this.surfaceView;
    this.surfaceView = null;
    if (surfaceView == null) {
      return;
    }
    ViewParent parent = surfaceView.getParent();
    if (parent instanceof ViewGroup) {
      ((ViewGroup) parent).removeView(surfaceView);
    }
  }
}
