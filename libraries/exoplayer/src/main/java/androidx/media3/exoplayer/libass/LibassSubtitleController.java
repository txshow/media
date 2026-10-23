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
package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.text.SubtitleSelectionState;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.text.SecondaryTextOutput;
import androidx.media3.exoplayer.trackselection.SecondaryTextTrackSelector;

/**
 * Controls secondary (e.g. libass-rendered) subtitle track selection and output.
 *
 * <p>This is a downgrade implementation: the controller holds references to its collaborators but
 * does not drive any real track selection. {@link #getSubtitleSelectionState()} always returns
 * {@link SubtitleSelectionState#EMPTY}, which means {@code activeSecondarySelection} is {@code
 * null} and {@code isPrimary}/{@code isActiveSecondary} checks on the caller side return {@code
 * false}. This keeps the fongmi logic safe: no secondary track is ever considered active.
 */
@UnstableApi
public final class LibassSubtitleController {

  private final Player player;
  private final LibassPlaybackSession libassPlaybackSession;
  private final SecondaryTextTrackSelector.Factory secondaryTextTrackSelectorFactory;
  private final SecondaryTextOutput secondaryTextOutput;

  @Nullable private TrackSelectionOverride secondaryOverride;
  private boolean secondaryAutoSelectionEnabled = true;
  private boolean closed;

  /**
   * Creates a new controller.
   *
   * @param player The {@link Player}.
   * @param libassPlaybackSession The libass playback session.
   * @param secondaryTextTrackSelectorFactory The factory for the secondary text track selector.
   * @param secondaryTextOutput The secondary text output.
   */
  public LibassSubtitleController(
      Player player,
      LibassPlaybackSession libassPlaybackSession,
      SecondaryTextTrackSelector.Factory secondaryTextTrackSelectorFactory,
      SecondaryTextOutput secondaryTextOutput) {
    this.player = player;
    this.libassPlaybackSession = libassPlaybackSession;
    this.secondaryTextTrackSelectorFactory = secondaryTextTrackSelectorFactory;
    this.secondaryTextOutput = secondaryTextOutput;
  }

  /**
   * Returns the current subtitle selection state.
   *
   * <p>The downgrade implementation always returns {@link SubtitleSelectionState#EMPTY}.
   */
  public SubtitleSelectionState getSubtitleSelectionState() {
    return SubtitleSelectionState.EMPTY;
  }

  /**
   * Sets an explicit override for the secondary text track.
   *
   * <p>Downgrade: the override is stored for later inspection but not applied to the player.
   *
   * @param override The override, or {@code null} to clear.
   */
  public synchronized void setSecondaryTextTrackSelectionOverride(
      @Nullable TrackSelectionOverride override) {
    this.secondaryOverride = override;
  }

  /**
   * Enables or disables auto-selection of the secondary text track.
   *
   * <p>Downgrade: the flag is stored but not applied to the player.
   *
   * @param enabled Whether auto-selection is enabled.
   */
  public synchronized void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {
    this.secondaryAutoSelectionEnabled = enabled;
  }

  /** Releases resources held by this controller. */
  public synchronized void close() {
    closed = true;
    secondaryOverride = null;
  }
}
