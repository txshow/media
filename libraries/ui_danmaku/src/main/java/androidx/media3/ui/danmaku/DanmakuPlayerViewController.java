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
 *
 */
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;

/**
 * A high-level controller that wires danmaku loading and rendering into a player view.
 *
 * <p>The controller is created unbound and can receive configuration ({@link #setOkHttpClient},
 * {@link #setConfig}, {@link #setEnabled}, {@link #setDataSource}) before {@link #bind(View)} is
 * called; those values are stored and pushed to the underlying {@link DanmakuController} once a
 * view is attached.
 *
 * <p>{@link #bind(View)} accepts any view. If the view exposes a {@code getDanmakuController()}
 * method (as {@code androidx.media3.ui.PlayerView} does), the controller delegates all loading and
 * rendering calls to that built-in {@link DanmakuController}; otherwise it falls back to an
 * internal {@link DanmakuController} that is not attached to any rendered view.
 *
 * <p>{@link #getItems()} returns an empty list: the loaded item set lives behind the {@link
 * DanmakuController} timeline and is not mirrored here.
 */
@UnstableApi
public final class DanmakuPlayerViewController {

  private final DanmakuController fallbackController = new DanmakuController();
  @Nullable private DanmakuController boundController;
  @Nullable private View boundView;
  @Nullable private OkHttpClient pendingOkHttpClient;
  @Nullable private DanmakuConfig pendingConfig;
  @Nullable private Uri pendingDataSource;
  private boolean enabled = true;
  private boolean enabledPending;
  private boolean hasEnabledPending;

  /** Creates a new, unbound controller. */
  public DanmakuPlayerViewController() {}

  /**
   * Returns the currently loaded danmaku items.
   *
   * <p>This stub always returns an immutable empty list; the actual loaded items are owned by the
   * {@link DanmakuController}'s timeline and are not mirrored here.
   */
  public List<Danmaku> getItems() {
    return Collections.emptyList();
  }

  /**
   * Binds this controller to the given view. If the view exposes a {@code getDanmakuController()}
   * method, calls are delegated to that built-in controller; otherwise an internal fallback
   * controller is used. Any configuration supplied before binding is pushed to the resolved
   * controller immediately.
   */
  public void bind(View view) {
    boundView = view;
    DanmakuController controller = resolveBuiltInController(view);
    if (controller == null) {
      controller = fallbackController;
    }
    boundController = controller;
    if (pendingOkHttpClient != null) {
      controller.setOkHttpClient(pendingOkHttpClient);
      pendingOkHttpClient = null;
    }
    if (pendingConfig != null) {
      controller.setConfig(pendingConfig);
      pendingConfig = null;
    }
    if (hasEnabledPending) {
      controller.setEnabled(enabledPending);
      hasEnabledPending = false;
    }
    if (pendingDataSource != null) {
      controller.setDataSource(pendingDataSource);
      pendingDataSource = null;
    }
  }

  @Nullable
  private static DanmakuController resolveBuiltInController(View view) {
    try {
      Method method = view.getClass().getMethod("getDanmakuController");
      Object result = method.invoke(view);
      if (result instanceof DanmakuController) {
        return (DanmakuController) result;
      }
    } catch (ReflectiveOperationException ignored) {
      // Fall through to the internal controller.
    }
    return null;
  }

  /** Sets the {@link OkHttpClient} used to fetch HTTP/HTTPS danmaku sources. */
  public void setOkHttpClient(@Nullable OkHttpClient client) {
    if (boundController != null) {
      boundController.setOkHttpClient(client);
    } else {
      pendingOkHttpClient = client;
    }
  }

  /** Sets whether danmaku rendering is enabled. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (boundController != null) {
      boundController.setEnabled(enabled);
    } else {
      enabledPending = enabled;
      hasEnabledPending = true;
    }
  }

  /** Sets the rendering configuration. */
  public void setConfig(DanmakuConfig config) {
    if (boundController != null) {
      boundController.setConfig(config);
    } else {
      pendingConfig = config;
    }
  }

  /** Sets the danmaku source URI, or {@code null} to clear the current source. */
  public void setDataSource(@Nullable Uri uri) {
    if (boundController != null) {
      boundController.setDataSource(uri);
    } else {
      pendingDataSource = uri;
    }
  }

  /** Sends a danmaku item at the current playback position. */
  public void sendNow(String text) {
    if (boundController != null) {
      boundController.sendNow(text);
    }
  }

  /** Releases the bound controller and clears pending state. */
  public void close() {
    if (boundController != null && boundController != fallbackController) {
      boundController.release();
    }
    fallbackController.release();
    boundController = null;
    boundView = null;
    pendingOkHttpClient = null;
    pendingConfig = null;
    pendingDataSource = null;
    hasEnabledPending = false;
  }
}
