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
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import androidx.media3.ui.danmaku.parser.Parser;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Owns the cancellation and worker-thread resources of one danmaku source load. */
final class DanmakuLoadTask implements Runnable {

  private static final int SNIFF_LENGTH = 512;
  private final Handler mainHandler;
  private final DanmakuSourceLoader.Request request;
  private final DanmakuSourceLoader.Listener listener;
  private final Object lock = new Object();
  private volatile boolean canceled;
  @Nullable private Thread workerThread;

  DanmakuLoadTask(
      Handler mainHandler,
      DanmakuSourceLoader.Request request,
      DanmakuSourceLoader.Listener listener) {
    this.mainHandler = mainHandler;
    this.request = request;
    this.listener = listener;
  }

  int generation() {
    return request.generation;
  }

  void cancel() {
    canceled = true;
    if (request.client != null) {
      request.client.dispatcher().cancelAll();
    }
    @Nullable Thread thread;
    synchronized (lock) {
      thread = workerThread;
    }
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public void run() {
    synchronized (lock) {
      if (canceled) {
        return;
      }
      workerThread = Thread.currentThread();
    }
    try {
      load();
    } catch (IOException e) {
      postLoadError(e);
    } catch (RuntimeException e) {
      postLoadError(new IOException("Unexpected runtime exception while loading danmaku", e));
    } finally {
      synchronized (lock) {
        workerThread = null;
      }
      Thread.interrupted();
    }
  }

  private static List<Danmaku> parse(Uri uri, byte[] data, List<Parser> parsers)
      throws IOException {
    if (data.length == 0) {
      return Collections.emptyList();
    }
    InputStream input = new BufferedInputStream(new ByteArrayInputStream(data));
    for (Parser parser : parsers) {
      input.mark(SNIFF_LENGTH);
      boolean matched = parser.sniff(input, SNIFF_LENGTH);
      input.reset();
      if (matched) {
        return parser.parse(input);
      }
    }
    throw new IOException("Unsupported danmaku format: " + uri);
  }

  private static Danmaku[] sortedArray(List<Danmaku> items) {
    Danmaku[] sorted = items.toArray(new Danmaku[0]);
    Arrays.sort(sorted, Danmaku.BY_TIME);
    return sorted;
  }

  private void load() throws IOException {
    if (canceled) {
      return;
    }
    for (Fetcher fetcher : request.fetchers) {
      if (fetcher.accepts(request.uri)) {
        loadFetcher(fetcher);
        return;
      }
    }
    loadDataSource();
  }

  private void loadFetcher(Fetcher fetcher) throws IOException {
    if (request.client == null) {
      throw new IOException(
          "OkHttpClient not set; call DanmakuController.setOkHttpClient() before fetching");
    }
    Fetcher.Session session = fetcher.prepare(request.uri, request.client, request.durationMs);
    if (canceled) {
      session.release();
      return;
    }
    int segmentDurationMs = session.segmentDurationMs();
    int segmentCount = session.segmentCount();
    if (segmentDurationMs <= 0 || segmentCount <= 0) {
      session.release();
      throw new IOException(
          "Invalid danmaku session metadata: segmentDurationMs="
              + segmentDurationMs
              + ", segmentCount="
              + segmentCount);
    }
    mainHandler.post(
        () -> listener.onSessionPrepared(request, session, segmentDurationMs, segmentCount));
  }

  private void loadDataSource() throws IOException {
    if (request.factory == null) {
      throw new IOException(
          "No fetcher accepted the URI and no DataSource.Factory was provided: " + request.uri);
    }
    DataSource dataSource = request.factory.createDataSource();
    try {
      dataSource.open(new DataSpec(request.uri));
      if (canceled) {
        return;
      }
      byte[] data = DataSourceUtil.readToEnd(dataSource);
      if (canceled) {
        return;
      }
      Danmaku[] items = sortedArray(parse(request.uri, data, request.parsers));
      mainHandler.post(() -> listener.onItemsLoaded(request, items));
    } finally {
      DataSourceUtil.closeQuietly(dataSource);
    }
  }

  private void postLoadError(IOException error) {
    if (!canceled) {
      mainHandler.post(() -> listener.onLoadError(request, error));
    }
  }
}
