/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.source.iso;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.upstream.Loader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class IsoParseLoadable implements Loader.Loadable {

  final long loadTaskId;
  final DataSpec dataSpec;

  private final MediaItem mediaItem;
  private final DataSource.Factory dataSourceFactory;
  private final AtomicBoolean canceled = new AtomicBoolean();

  private final AtomicReference<IsoParsedMedia> result = new AtomicReference<>();

  IsoParseLoadable(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri) {
    loadTaskId = LoadEventInfo.getNewId();
    dataSpec = new DataSpec(isoUri);
    this.dataSourceFactory = dataSourceFactory;
    this.mediaItem = mediaItem;
  }

  @Override
  public void cancelLoad() {
    canceled.set(true);
    releaseResult();
  }

  @Override
  public void load() throws IOException {
    @Nullable
    IsoParsedMedia parsedMedia =
        IsoParsedMedia.parse(mediaItem, dataSourceFactory, dataSpec.uri, canceled);
    if (parsedMedia == null) {
      return;
    }
    if (canceled.get()) {
      parsedMedia.close();
      return;
    }
    result.set(parsedMedia);
    if (canceled.get()) {
      releaseResult();
    }
  }

  @Nullable
  IsoParsedMedia takeResult() {
    return result.getAndSet(null);
  }

  void releaseResult() {
    IsoParsedMedia parsedMedia = takeResult();
    if (parsedMedia != null) {
      parsedMedia.close();
    }
  }
}
