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
 *
 */
package androidx.media3.ui.danmaku.fetcher;

import android.net.Uri;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.danmaku.Danmaku;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;

/** Fetches segmented danmaku data for supported media page URIs. */
@UnstableApi
public interface Fetcher {

  /** Returns whether this fetcher supports {@code uri}. */
  boolean accepts(Uri uri);

  /** Prepares a fetch session for {@code uri}. */
  @WorkerThread
  Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException;

  /** A segmented danmaku fetch session. */
  interface Session {

    /** Returns the number of available segments. */
    int segmentCount();

    /** Returns the duration of each segment in milliseconds. */
    default int segmentDurationMs() {
      return 60_000;
    }

    /** Fetches the one-based segment numbered {@code segmentNumber}. */
    @WorkerThread
    List<Danmaku> fetchSegment(int segmentNumber) throws IOException;

    /** Releases resources owned by this session. */
    default void release() {}
  }
}
