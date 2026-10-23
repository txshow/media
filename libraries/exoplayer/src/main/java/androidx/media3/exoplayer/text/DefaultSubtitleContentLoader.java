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
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;

/**
 * Default {@link SubtitleContentLoader} that reads subtitle data through a {@link DataSource}.
 *
 * <p>This is a degraded implementation: it fully reads the subtitle source so that request headers
 * and transfer listeners are honoured, but it does not yet parse the bytes into rich content and
 * returns {@link SubtitleContent#UNSUPPORTED}.
 */
@UnstableApi
public class DefaultSubtitleContentLoader implements SubtitleContentLoader {

  private static final int READ_BUFFER_SIZE = 64 * 1024;

  private final DataSource.Factory dataSourceFactory;

  /** @param dataSourceFactory A {@link DataSource.Factory} used to open the subtitle source. */
  public DefaultSubtitleContentLoader(DataSource.Factory dataSourceFactory) {
    this.dataSourceFactory = dataSourceFactory;
  }

  @Override
  public SubtitleContent load(MediaItem item, Uri uri, Format format) throws IOException {
    DataSource dataSource = dataSourceFactory.createDataSource();
    try {
      dataSource.open(new DataSpec(uri));
      byte[] readBuffer = new byte[READ_BUFFER_SIZE];
      while (dataSource.read(readBuffer, 0, READ_BUFFER_SIZE) != -1) {
        // Drain the source so headers/transfer listeners are observed. Parsing is intentionally
        // not performed in this degraded implementation.
      }
      return SubtitleContent.UNSUPPORTED;
    } finally {
      dataSource.close();
    }
  }
}
