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
package androidx.media3.exoplayer.iso;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.DiscMenuController;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.iso.IsoMediaSource;

/**
 * Bridges ISO/BDMV/DVD/SACD disc playback to the {@link DiscMenuController} interface.
 *
 * <p>This is a degraded implementation: it recognises disc images and builds the matching {@link
 * IsoMediaSource}, but disc menus are not rendered, so all menu interaction methods report that no
 * navigation playback is active.
 */
@UnstableApi
public class IsoNavigationPlayerController implements DiscMenuController {

  private final ExoPlayer player;
  private boolean started;

  /** @param player The {@link ExoPlayer} that this controller navigates. */
  public IsoNavigationPlayerController(ExoPlayer player) {
    this.player = player;
  }

  /** Returns the {@link ExoPlayer} passed to the constructor. */
  public ExoPlayer getPlayer() {
    return player;
  }

  /**
   * Returns an ISO-specific {@link MediaSource} when the provided source refers to a disc image, or
   * the passed-through {@code source} unchanged for regular media so that normal playback proceeds.
   *
   * @param context A {@link Context}.
   * @param source The regular {@link MediaSource} built for the current {@link MediaItem}.
   * @param dataSourceFactory A {@link DataSource.Factory} used to open the disc image.
   * @return An {@link IsoMediaSource} for disc images, or the given {@code source} otherwise.
   */
  @Nullable
  public MediaSource prepareMediaSource(
      Context context, MediaSource source, DataSource.Factory dataSourceFactory) {
    MediaItem mediaItem = source.getMediaItem();
    if (!IsoDiscPreparation.isIso(mediaItem)) {
      return source;
    }
    started = true;
    return new IsoMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
  }

  /** Returns whether an ISO navigation playback session is currently active. */
  public boolean isStarted() {
    return started;
  }

  // DiscMenuController implementation (degraded: no on-screen disc menus).

  @Override
  public boolean open(String action, Callback callback) {
    return false;
  }

  @Override
  public boolean sendAction(String action) {
    return false;
  }

  @Override
  public boolean isActive() {
    return false;
  }

  @Override
  public boolean supportsPointer() {
    return false;
  }

  @Override
  public boolean isNavigationPlayback() {
    return false;
  }

  @Override
  public void close() {
    started = false;
  }
}
