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

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Schedules, retries, and delivers segments from one fetcher session. */
final class DanmakuSegmentLoader {

  private static final int MAX_AHEAD_SEGMENTS = 2;
  private final Handler mainHandler;
  private final Host host;
  private final DanmakuSegmentState state = new DanmakuSegmentState();
  private final long loadCheckIntervalMs;
  private final long backwardFillDelayMs;
  private final long backwardFillSeekDelayMs;
  @Nullable private Handler backgroundHandler;
  private int generation = -1;

  DanmakuSegmentLoader(
      Handler mainHandler,
      Host host,
      long loadCheckIntervalMs,
      long backwardFillDelayMs,
      long backwardFillSeekDelayMs) {
    this.mainHandler = mainHandler;
    this.host = host;
    this.loadCheckIntervalMs = loadCheckIntervalMs;
    this.backwardFillDelayMs = backwardFillDelayMs;
    this.backwardFillSeekDelayMs = backwardFillSeekDelayMs;
  }

  private static Danmaku[] sortedArray(List<Danmaku> items) {
    Danmaku[] sorted = items.toArray(new Danmaku[0]);
    Arrays.sort(sorted, Danmaku.BY_TIME);
    return sorted;
  }

  void start(
      Handler backgroundHandler,
      Fetcher.Session session,
      int segmentDurationMs,
      int segmentCount,
      long startPositionMs,
      long timeOffsetMs,
      int generation) {
    this.backgroundHandler = backgroundHandler;
    this.generation = generation;
    state.start(session, segmentDurationMs, segmentCount, startPositionMs, timeOffsetMs);
    scheduleNext();
  }

  void reset(long positionMs, long timeOffsetMs) {
    if (!state.hasSession()) {
      return;
    }
    state.resetCursors(positionMs, timeOffsetMs, true);
    scheduleNext();
  }

  void resetForTimeOffsetChange(long positionMs, long oldTimeOffsetMs, long newTimeOffsetMs) {
    if (!state.hasSession() || state.isSameSegment(positionMs, oldTimeOffsetMs, newTimeOffsetMs)) {
      return;
    }
    reset(positionMs, newTimeOffsetMs);
  }

  boolean hasSession() {
    return state.hasSession();
  }

  void cancel() {
    Handler handler = backgroundHandler;
    @Nullable Fetcher.Session session = state.clear();
    generation = -1;
    backgroundHandler = null;
    if (session == null) {
      return;
    }
    if (handler != null) {
      handler.post(session::release);
    } else {
      session.release();
    }
  }

  private void scheduleNext() {
    Handler handler = backgroundHandler;
    if (handler == null || !state.hasSession() || !host.isCurrent(generation)) {
      return;
    }
    int currentSegment =
        host.hasPlayer() ? state.segmentAt(host.currentPositionMs(), host.timeOffsetMs()) : 1;
    @Nullable
    DanmakuSegmentState.Decision decision = state.next(currentSegment, MAX_AHEAD_SEGMENTS);
    if (decision == null) {
      return;
    }
    if (decision.action == DanmakuSegmentState.Decision.ACTION_CHECK_LATER) {
      scheduleCheckLater(handler);
      return;
    }
    schedule(decision.segment, decision.retry, delayMs(decision.delay));
  }

  private void scheduleCheckLater(Handler handler) {
    int requestGeneration = generation;
    handler.postDelayed(
        () -> mainHandler.post(() -> scheduleNext(requestGeneration)), loadCheckIntervalMs);
  }

  private void scheduleNext(int requestGeneration) {
    if (generation == requestGeneration && host.isCurrent(requestGeneration)) {
      scheduleNext();
    }
  }

  private long delayMs(@DanmakuSegmentState.Decision.Delay int delay) {
    switch (delay) {
      case DanmakuSegmentState.Decision.DELAY_BACKWARD_FILL:
        return backwardFillDelayMs;
      case DanmakuSegmentState.Decision.DELAY_AFTER_SEEK:
        return backwardFillSeekDelayMs;
      default:
        return 0L;
    }
  }

  private void schedule(int segment, boolean retry, long delayMs) {
    Handler handler = backgroundHandler;
    @Nullable Fetcher.Session session = state.session();
    if (handler == null || session == null || !state.markLoading(segment)) {
      return;
    }
    int requestGeneration = generation;
    handler.postDelayed(() -> fetch(session, segment, requestGeneration, retry), delayMs);
  }

  private void fetch(Fetcher.Session session, int segment, int requestGeneration, boolean retry) {
    if (!host.isCurrent(requestGeneration)) {
      return;
    }
    Danmaku[] items;
    @Nullable IOException error = null;
    try {
      items = sortedArray(session.fetchSegment(segment));
    } catch (IOException e) {
      items = new Danmaku[0];
      error = e;
    }
    Danmaku[] result = items;
    @Nullable IOException failure = error;
    mainHandler.post(() -> deliver(session, segment, requestGeneration, retry, result, failure));
  }

  private void deliver(
      Fetcher.Session session,
      int segment,
      int requestGeneration,
      boolean retry,
      Danmaku[] items,
      @Nullable IOException error) {
    if (!host.isCurrent(requestGeneration)) {
      return;
    }
    state.finishFetch(segment);
    if (error != null) {
      handleFetchError(session, segment, retry, error);
      return;
    }
    if (!state.markLoaded(segment)) {
      scheduleNext();
      return;
    }
    if (items.length > 0) {
      host.onItems(items);
    }
    host.onProgress(state.loadedCount(), state.totalSegments());
    if (!host.isCurrent(requestGeneration)) {
      return;
    }
    if (state.isComplete()) {
      state.finish(session);
      host.onCompleted(requestGeneration);
      return;
    }
    scheduleNext();
  }

  private void handleFetchError(
      Fetcher.Session session, int segment, boolean retry, IOException error) {
    if (!retry) {
      state.markFailed(segment);
      scheduleNext();
      return;
    }
    state.fail(session);
    host.onFailed(segment, error);
  }

  interface Host {

    boolean isCurrent(int generation);

    boolean hasPlayer();

    long currentPositionMs();

    long timeOffsetMs();

    void onItems(Danmaku[] items);

    void onProgress(int loaded, int total);

    void onCompleted(int generation);

    void onFailed(int segment, IOException cause);
  }
}
