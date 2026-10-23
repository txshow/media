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
package androidx.media3.exoplayer.text;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;

/**
 * Owns the lifecycle of on-demand subtitle content (e.g. external subtitle files) for an ExoPlayer
 * session.
 *
 * <p>This is a downgrade implementation: content fetching via {@link SubtitleContentLoader} is
 * supported as a best-effort, but failures degrade to {@link SubtitleContent#UNSUPPORTED} and
 * retry is always reported as not possible ({@link #canRetryContent(Format)} returns {@code false}).
 * All mutators are safe to call from any thread; internal state is guarded by {@code synchronized}.
 */
@UnstableApi
public class SubtitleTranscriptSession {

  private static final SubtitleTranscript EMPTY_TRANSCRIPT =
      new SubtitleTranscript() {
        @Override
        public boolean isEmpty() {
          return true;
        }
      };

  @Nullable private SubtitleContentLoader loader;
  @Nullable private Player player;
  @Nullable private MediaItem preloadMediaItem;
  private boolean contentEnabled = true;
  private boolean closed;

  /** Creates a new session. */
  public SubtitleTranscriptSession() {}

  /**
   * Sets the {@link SubtitleContentLoader} used to fetch on-demand content.
   *
   * @param loader The loader, or {@code null} to disable loading.
   */
  public synchronized void setLoader(@Nullable SubtitleContentLoader loader) {
    this.loader = loader;
  }

  /**
   * Sets the {@link Player} this session is associated with.
   *
   * @param player The player.
   */
  public synchronized void setPlayer(Player player) {
    this.player = player;
  }

  /**
   * Sets the {@link MediaItem} being preloaded, used as a fallback when resolving an external
   * subtitle URI.
   *
   * @param mediaItem The preload media item, or {@code null}.
   */
  public synchronized void setPreloadMediaItem(@Nullable MediaItem mediaItem) {
    this.preloadMediaItem = mediaItem;
  }

  /**
   * Returns a {@link SubtitleTranscript} for the given {@link MediaItem}.
   *
   * <p>The downgrade implementation always returns a shared empty transcript; the parser factory
   * built from it will not produce any cues.
   *
   * @param mediaItem The media item.
   * @return The transcript for the media item.
   */
  public SubtitleTranscript forMediaItem(MediaItem mediaItem) {
    return EMPTY_TRANSCRIPT;
  }

  /**
   * Returns the {@link SubtitleContent} for the given {@link Format}, applying {@code offsetMs}.
   *
   * <p>If a {@link SubtitleContentLoader} has been set and the player's current media item (or the
   * preload item) exposes an external subtitle URI, the loader is invoked on a best-effort basis.
   * Any failure degrades to {@link SubtitleContent#UNSUPPORTED}.
   *
   * @param format The subtitle track format.
   * @param offsetMs The subtitle offset in milliseconds.
   * @return The content, or {@link SubtitleContent#UNSUPPORTED} if unavailable.
   */
  public synchronized SubtitleContent getContent(Format format, long offsetMs) {
    if (!contentEnabled || closed) {
      return SubtitleContent.UNSUPPORTED;
    }
    SubtitleContentLoader loader = this.loader;
    Player player = this.player;
    if (loader == null) {
      return SubtitleContent.UNSUPPORTED;
    }
    MediaItem item =
        player != null && player.getCurrentMediaItem() != null
            ? player.getCurrentMediaItem()
            : preloadMediaItem;
    if (item == null) {
      return SubtitleContent.UNSUPPORTED;
    }
    @Nullable Uri uri = resolveSubtitleUri(item, format);
    if (uri == null) {
      return SubtitleContent.UNSUPPORTED;
    }
    try {
      return loader.load(item, uri, format);
    } catch (IOException | RuntimeException e) {
      return SubtitleContent.UNSUPPORTED;
    }
  }

  /**
   * Enables or disables on-demand subtitle content loading.
   *
   * @param enabled Whether content loading is enabled.
   */
  public synchronized void setContentEnabled(boolean enabled) {
    this.contentEnabled = enabled;
  }

  /**
   * Returns whether a failed content load for {@code format} can be retried.
   *
   * <p>Downgrade implementation always returns {@code false}.
   */
  public boolean canRetryContent(Format format) {
    return false;
  }

  /**
   * Retries a previously failed content load for {@code format}.
   *
   * <p>Downgrade implementation is a no-op.
   */
  public void retryContent(Format format) {
    // No-op: retry not supported in downgrade.
  }

  /** Releases resources held by this session. */
  public synchronized void close() {
    closed = true;
    loader = null;
    player = null;
    preloadMediaItem = null;
  }

  @Nullable
  private static Uri resolveSubtitleUri(MediaItem item, Format format) {
    if (item.localConfiguration != null
        && item.localConfiguration.subtitleConfigurations != null) {
      for (MediaItem.SubtitleConfiguration subtitle : item.localConfiguration.subtitleConfigurations) {
        if (format.id != null && format.id.equals(subtitle.id)) {
          return subtitle.uri;
        }
      }
    }
    return null;
  }
}
