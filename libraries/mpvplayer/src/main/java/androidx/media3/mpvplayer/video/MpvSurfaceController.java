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
package androidx.media3.mpvplayer.video;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.Size;
import androidx.media3.mpvplayer.nativebridge.MpvClient;
import androidx.media3.mpvplayer.options.MpvPerFileOptions;

public final class MpvSurfaceController {

  private final MpvNativeSurface nativeSurface;
  private final MpvSurfaceTarget surfaceTarget;
  private final Host host;

  @Nullable private Object videoOutput;

  public MpvSurfaceController(MpvClient client, Host host) {
    this.nativeSurface = new MpvNativeSurface(client, host);
    this.surfaceTarget = new MpvSurfaceTarget(new TargetHost());
    this.host = host;
  }

  public Size getSurfaceSize() {
    return nativeSurface.getSurfaceSize();
  }

  @RestrictTo(LIBRARY_GROUP)
  public void addPerFileOptions(MpvPerFileOptions options) {
    nativeSurface.addPerFileOptions(options);
  }

  public void setVideoOutput(Object videoOutput) {
    this.videoOutput = videoOutput;
    if (host.isInitialized()) {
      applyVideoOutput(videoOutput);
    }
  }

  private void applyVideoOutput(Object videoOutput) {
    updateDisplayPeakLuminance(videoOutput instanceof View ? (View) videoOutput : null);
    if (videoOutput instanceof SurfaceView) {
      surfaceTarget.setSurfaceView((SurfaceView) videoOutput);
    } else if (videoOutput instanceof TextureView) {
      surfaceTarget.setTextureView((TextureView) videoOutput);
    } else if (videoOutput instanceof SurfaceHolder) {
      surfaceTarget.setSurfaceHolder((SurfaceHolder) videoOutput);
    } else if (videoOutput instanceof Surface) {
      surfaceTarget.setSurface((Surface) videoOutput);
    } else {
      surfaceTarget.clear();
    }
  }

  @SuppressWarnings("deprecation")
  private void updateDisplayPeakLuminance(@Nullable View view) {
    double displayPeakLuminance = 0;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && view != null) {
      Display display = view.getDisplay();
      if (display != null) {
        Display.HdrCapabilities capabilities = display.getHdrCapabilities();
        if (capabilities != null
            && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? display.isHdr()
                : capabilities.getSupportedHdrTypes().length > 0)) {
          float desiredMaxLuminance = capabilities.getDesiredMaxLuminance();
          if (!Float.isNaN(desiredMaxLuminance)
              && !Float.isInfinite(desiredMaxLuminance)
              && desiredMaxLuminance > 0) {
            displayPeakLuminance = desiredMaxLuminance;
          }
        }
      }
    }
    nativeSurface.setDisplayPeakLuminance(displayPeakLuminance);
  }

  public void clearVideoOutput(@Nullable Object videoOutput) {
    if (videoOutput != null && videoOutput != this.videoOutput) {
      return;
    }
    updateDisplayPeakLuminance(null);
    surfaceTarget.clear();
    this.videoOutput = null;
  }

  public void onInitialized() {
    if (videoOutput != null) {
      setVideoOutput(videoOutput);
    }
  }

  @RestrictTo(LIBRARY_GROUP)
  public void onNativeSessionEnded() {
    nativeSurface.onNativeSessionEnded();
    surfaceTarget.onNativeSessionEnded();
  }

  public void release() {
    if (!host.isInitialized()) {
      onNativeSessionEnded();
      return;
    }
    surfaceTarget.prepareForRelease();
  }

  private boolean setSurfaceInternal(@Nullable Surface surface) {
    MpvNativeSurface.SurfaceChange change = nativeSurface.setSurface(surface);
    if (!change.changed()) {
      return false;
    }
    if (change.resetsFirstFrame()) {
      host.resetRenderedFirstFrame();
    }
    return true;
  }

  private void updateSurfaceSize(Size size) {
    if (nativeSurface.setSurfaceSize(size)) {
      host.invalidateState();
    }
  }

  public interface Host {

    void runOnPlayerLooper(Runnable runnable);

    void runOnPlayerLooperAndWait(Runnable runnable);

    boolean isInitialized();

    void resetRenderedFirstFrame();

    void invalidateState();
  }

  private final class TargetHost implements MpvSurfaceTarget.Host {

    @Override
    public void runOnPlayerLooper(Runnable runnable) {
      host.runOnPlayerLooper(runnable);
    }

    @Override
    public void runOnPlayerLooperAndWait(Runnable runnable) {
      host.runOnPlayerLooperAndWait(runnable);
    }

    @Override
    public boolean onSurfaceChanged(@Nullable Surface surface) {
      updateDisplayPeakLuminance(videoOutput instanceof View ? (View) videoOutput : null);
      return setSurfaceInternal(surface);
    }

    @Override
    public void onSurfaceSizeChanged(Size size) {
      updateSurfaceSize(size);
    }
  }
}
