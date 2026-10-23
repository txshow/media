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
 */
package androidx.media3.exoplayer.source;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * A {@link MediaSource.Factory} that wraps a delegate factory and resolves the {@link
 * C.ContentType content type} for each {@link MediaItem} before delegating.
 *
 * <p>The delegate (typically a {@link DefaultMediaSourceFactory}) already selects the concrete
 * media source based on the URI extension / mimetype. This wrapper exists so that application code
 * has a single, explicit entry point that can apply additional content-type resolution (for example
 * sniffing ambiguous container URIs) without duplicating the delegate's configuration.
 */
@UnstableApi
public final class ContentTypeResolvingMediaSource {

  private ContentTypeResolvingMediaSource() {}

  /** Factory for {@link ContentTypeResolvingMediaSource}. */
  @UnstableApi
  public static final class Factory implements MediaSource.Factory {

    private final MediaSource.Factory delegate;
    private final DataSource.Factory dataSourceFactory;

    /**
     * Creates a new factory.
     *
     * @param delegate The delegate factory that builds the concrete {@link MediaSource}.
     * @param dataSourceFactory The {@link DataSource.Factory} used to open media data.
     */
    public Factory(MediaSource.Factory delegate, DataSource.Factory dataSourceFactory) {
      this.delegate = delegate;
      this.dataSourceFactory = dataSourceFactory;
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
      return delegate.createMediaSource(mediaItem);
    }

    @Override
    public @C.ContentType int[] getSupportedTypes() {
      return delegate.getSupportedTypes();
    }

    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(
        DrmSessionManagerProvider drmSessionManagerProvider) {
      delegate.setDrmSessionManagerProvider(drmSessionManagerProvider);
      return this;
    }

    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(
        LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
      return this;
    }

    @Override
    public MediaSource.Factory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
      delegate.setSubtitleParserFactory(subtitleParserFactory);
      return this;
    }
  }
}
