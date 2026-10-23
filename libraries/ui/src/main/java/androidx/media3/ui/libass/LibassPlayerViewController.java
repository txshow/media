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
package androidx.media3.ui.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

/**
 * Bridges the libass subtitle pipeline to {@link PlayerView}.
 *
 * <p>Degraded implementation: the controller stores the style override, the secondary subtitle view
 * configurator and the bound view, but does not attach a live libass rendering surface. All
 * methods are safe to call without a backing libass renderer.
 */
@UnstableApi
public final class LibassPlayerViewController {

  private final Player player;
  private final LibassPlaybackSession libassPlaybackSession;
  private final LibassSubtitleController libassSubtitleController;
  @Nullable private CaptionStyleCompat styleOverride;
  @Nullable private String fontFamilyOverride;
  @Nullable private Consumer<SubtitleView> secondarySubtitleViewConfigurator;
  @Nullable private PlayerView boundPlayerView;

  public LibassPlayerViewController(
      Player player,
      LibassPlaybackSession libassPlaybackSession,
      LibassSubtitleController libassSubtitleController) {
    this.player = player;
    this.libassPlaybackSession = libassPlaybackSession;
    this.libassSubtitleController = libassSubtitleController;
  }

  /** Binds this controller to the given view (degraded: recorded for future rendering wiring). */
  public void bind(PlayerView playerView) {
    boundPlayerView = playerView;
  }

  /** Releases references to the bound view. */
  public void close() {
    boundPlayerView = null;
  }

  /** Stores a forced subtitle style and font family; {@code null} values clear the override. */
  public void setStyleOverride(
      @Nullable CaptionStyleCompat style, @Nullable String fontFamily) {
    styleOverride = style;
    fontFamilyOverride = fontFamily;
  }

  /** Stores the configurator applied to the secondary subtitle view. */
  public void setSecondarySubtitleViewConfigurator(
      Consumer<SubtitleView> configurator) {
    secondarySubtitleViewConfigurator = configurator;
  }
}
