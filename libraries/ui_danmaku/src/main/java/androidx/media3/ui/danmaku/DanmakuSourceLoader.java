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
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import androidx.media3.ui.danmaku.parser.Parser;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;

/** Reads a danmaku source and owns cancellation of the active blocking request. */
final class DanmakuSourceLoader {

  private final Handler mainHandler;
  private final Object lock = new Object();
  @Nullable private DanmakuLoadTask activeTask;

  DanmakuSourceLoader(Handler mainHandler) {
    this.mainHandler = mainHandler;
  }

  void load(Handler backgroundHandler, Request request, Listener listener) {
    DanmakuLoadTask task = new DanmakuLoadTask(mainHandler, request, listener);
    @Nullable DanmakuLoadTask previousTask;
    synchronized (lock) {
      previousTask = activeTask;
      activeTask = task;
    }
    if (previousTask != null) {
      previousTask.cancel();
    }
    backgroundHandler.post(task);
  }

  void cancel() {
    @Nullable DanmakuLoadTask task;
    synchronized (lock) {
      task = activeTask;
      activeTask = null;
    }
    if (task != null) {
      task.cancel();
    }
  }

  void complete(int generation) {
    synchronized (lock) {
      if (activeTask != null && activeTask.generation() == generation) {
        activeTask = null;
      }
    }
  }

  interface Listener {

    void onSessionPrepared(
        Request request, Fetcher.Session session, int segmentDurationMs, int segmentCount);

    void onItemsLoaded(Request request, Danmaku[] items);

    void onLoadError(Request request, IOException error);
  }

  static final class Request {

    final Uri uri;
    @Nullable final DataSource.Factory factory;
    @Nullable final OkHttpClient client;
    final List<Fetcher> fetchers;
    final List<Parser> parsers;
    final int generation;
    final long startPositionMs;
    final long durationMs;

    Request(
        Uri uri,
        @Nullable DataSource.Factory factory,
        @Nullable OkHttpClient client,
        List<Fetcher> fetchers,
        List<Parser> parsers,
        int generation,
        long startPositionMs,
        long durationMs) {
      this.uri = uri;
      this.factory = factory;
      this.client = client;
      this.fetchers = fetchers;
      this.parsers = parsers;
      this.generation = generation;
      this.startPositionMs = startPositionMs;
      this.durationMs = durationMs;
    }
  }
}
