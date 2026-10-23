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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.MediaClock;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.text.SubtitleTranscript;
import androidx.media3.exoplayer.text.SubtitleTranscriptParserFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.IOException;

/**
 * Owns the libass-backed subtitle rendering session for an ExoPlayer playback.
 *
 * <p>This is a downgrade implementation: all rendering parameters are stored but no native libass
 * rendering is performed. The session still exposes a valid {@link Renderer} ({@link
 * #createClockRenderer()}) and media-component factories so that the fongmi call chain compiles and
 * runs without crashing; the renderer supports no formats and renders nothing.
 */
@UnstableApi
public final class LibassPlaybackSession {

  /**
   * The extractors and subtitle-parser factories to use when building a media source for this
   * session.
   */
  public static final class MediaComponents {
    public final ExtractorsFactory extractorsFactory;
    public final SubtitleParser.Factory subtitleParserFactory;

    public MediaComponents(
        ExtractorsFactory extractorsFactory, SubtitleParser.Factory subtitleParserFactory) {
      this.extractorsFactory = extractorsFactory;
      this.subtitleParserFactory = subtitleParserFactory;
    }
  }

  private final LibassConfiguration configuration;
  private final boolean enabled;
  @Nullable private MediaItem preloadMediaItem;
  private boolean closed;

  private float bottomPositionFraction;
  private float secondaryBottomPositionFraction;
  private float fontScale = 1.0f;
  private boolean fontScaleAppliedToSecondary;

  /**
   * Creates a new session.
   *
   * @param configuration The libass configuration.
   * @param enabled Whether libass rendering is enabled by the user.
   */
  public LibassPlaybackSession(LibassConfiguration configuration, boolean enabled) {
    this.configuration = configuration;
    this.enabled = enabled;
  }

  /** Returns the {@link LibassConfiguration} this session was created with. */
  public LibassConfiguration getConfiguration() {
    return configuration;
  }

  /**
   * Returns whether this session is available (i.e. the caller should attach its renderers and
   * media components).
   *
   * <p>The downgrade implementation returns the {@code enabled} flag passed to the constructor.
   * When {@code true}, {@link #createClockRenderer()} returns a valid no-op {@link Renderer}; when
   * {@code false}, callers should fall back to the default subtitle path.
   */
  public boolean isAvailable() {
    return enabled && !closed;
  }

  /**
   * Sets the bottom position fraction for the primary subtitle.
   *
   * @param fraction The fraction (0 = bottom, 1 = top).
   */
  public void setBottomPositionFraction(float fraction) {
    this.bottomPositionFraction = fraction;
  }

  /**
   * Sets the bottom position fraction for the secondary subtitle.
   *
   * @param fraction The fraction.
   */
  public void setSecondaryBottomPositionFraction(float fraction) {
    this.secondaryBottomPositionFraction = fraction;
  }

  /**
   * Sets the font scale for subtitles.
   *
   * @param scale The font scale factor.
   * @param applyToSecondary Whether the scale should also apply to the secondary subtitle.
   */
  public void setFontScale(float scale, boolean applyToSecondary) {
    this.fontScale = scale;
    this.fontScaleAppliedToSecondary = applyToSecondary;
  }

  /**
   * Sets the {@link MediaItem} currently being preloaded.
   *
   * @param mediaItem The preload media item, or {@code null}.
   */
  public void setPreloadMediaItem(@Nullable MediaItem mediaItem) {
    this.preloadMediaItem = mediaItem;
  }

  /**
   * Returns a clock {@link Renderer} that drives libass rendering.
   *
   * <p>The downgrade implementation returns a no-op renderer that supports no formats.
   */
  public Renderer createClockRenderer() {
    return new NoOpClockRenderer();
  }

  /**
   * Builds the {@link MediaComponents} for the given {@link MediaItem}.
   *
   * <p>The downgrade implementation returns the supplied {@link ExtractorsFactory} unchanged and
   * wraps the {@link SubtitleTranscript} in a {@link SubtitleTranscriptParserFactory}.
   */
  public MediaComponents createMediaComponents(
      MediaItem mediaItem, ExtractorsFactory extractorsFactory, SubtitleTranscript transcript) {
    SubtitleParser.Factory subtitleParserFactory =
        new SubtitleTranscriptParserFactory(transcript);
    return new MediaComponents(extractorsFactory, subtitleParserFactory);
  }

  /** Releases resources held by this session. */
  public void close() {
    closed = true;
    preloadMediaItem = null;
  }

  /** A {@link Renderer} that supports no formats and does nothing. */
  private static final class NoOpClockRenderer implements Renderer {

    private final RendererCapabilities capabilities = new NoOpRendererCapabilities();
    private int state = Renderer.STATE_DISABLED;

    @Override
    public String getName() {
      return "LibassPlaybackSession$NoOpClockRenderer";
    }

    @Override
    public int getTrackType() {
      return C.TRACK_TYPE_NONE;
    }

    @Override
    public RendererCapabilities getCapabilities() {
      return capabilities;
    }

    @Override
    public void init(int index, PlayerId playerId, Clock clock) {}

    @Override
    public @Nullable MediaClock getMediaClock() {
      return null;
    }

    @Override
    public int getState() {
      return state;
    }

    @Override
    public void enable(
        RendererConfiguration configuration,
        Format[] formats,
        SampleStream stream,
        long positionUs,
        boolean joining,
        boolean mayRenderStartOfStream,
        long startPositionUs,
        long offsetUs,
        MediaPeriodId mediaPeriodId) {
      state = Renderer.STATE_ENABLED;
    }

    @Override
    public void start() {
      state = Renderer.STATE_STARTED;
    }

    @Override
    public void replaceStream(
        Format[] formats,
        SampleStream stream,
        long startPositionUs,
        long offsetUs,
        MediaPeriodId mediaPeriodId) {}

    @Override
    public @Nullable SampleStream getStream() {
      return null;
    }

    @Override
    public boolean hasReadStreamToEnd() {
      return true;
    }

    @Override
    public long getReadingPositionUs() {
      return C.TIME_END_OF_SOURCE;
    }

    @Override
    public void setCurrentStreamFinal() {}

    @Override
    public boolean isCurrentStreamFinal() {
      return false;
    }

    @Override
    public void maybeThrowStreamError() throws IOException {}

    @Override
    public void resetPosition(long positionUs, boolean sampleStreamIsResetToKeyFrame) {}

    @Override
    public void setTimeline(Timeline timeline) {}

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public boolean isEnded() {
      return true;
    }

    @Override
    public void stop() {
      state = Renderer.STATE_ENABLED;
    }

    @Override
    public void disable() {
      state = Renderer.STATE_DISABLED;
    }

    @Override
    public void reset() {}

    @Override
    public void handleMessage(int messageType, @Nullable Object message)
        throws ExoPlaybackException {}
  }

  /** A {@link RendererCapabilities} that supports no formats. */
  private static final class NoOpRendererCapabilities implements RendererCapabilities {

    @Override
    public String getName() {
      return "LibassPlaybackSession$NoOpClockRenderer";
    }

    @Override
    public int getTrackType() {
      return C.TRACK_TYPE_NONE;
    }

    @Override
    public int supportsFormat(Format format) throws ExoPlaybackException {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
      return ADAPTIVE_NOT_SUPPORTED;
    }
  }
}
