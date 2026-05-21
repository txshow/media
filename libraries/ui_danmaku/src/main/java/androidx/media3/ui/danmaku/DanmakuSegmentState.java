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

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.Set;

/** Mutable state belonging to one segmented fetcher session. */
final class DanmakuSegmentState {

  private final Set<Integer> loaded = new HashSet<>();
  private final Set<Integer> loading = new HashSet<>();
  private final Set<Integer> failed = new HashSet<>();
  @Nullable private Fetcher.Session session;
  private int segmentDurationMs;
  private int totalSegments;
  private int nextForwardSegment;
  private int nextBackwardSegment;
  private boolean afterSeek;

  void start(
      Fetcher.Session session,
      int segmentDurationMs,
      int totalSegments,
      long positionMs,
      long timeOffsetMs) {
    release();
    this.session = session;
    this.segmentDurationMs = segmentDurationMs;
    this.totalSegments = totalSegments;
    loaded.clear();
    loading.clear();
    failed.clear();
    resetCursors(positionMs, timeOffsetMs, false);
  }

  void resetCursors(long positionMs, long timeOffsetMs, boolean afterSeek) {
    int currentSegment = segmentAt(positionMs, timeOffsetMs);
    nextForwardSegment = currentSegment;
    nextBackwardSegment = currentSegment - 1;
    failed.clear();
    this.afterSeek = afterSeek;
  }

  int segmentAt(long positionMs, long timeOffsetMs) {
    int current = Math.max(1, (int) ((positionMs - timeOffsetMs) / segmentDurationMs) + 1);
    return totalSegments > 0 ? Math.min(current, totalSegments) : current;
  }

  boolean isSameSegment(long positionMs, long firstTimeOffsetMs, long secondTimeOffsetMs) {
    return segmentAt(positionMs, firstTimeOffsetMs) == segmentAt(positionMs, secondTimeOffsetMs);
  }

  @Nullable
  Decision next(int currentSegment, int maxAheadSegments) {
    if (session == null || !loading.isEmpty()) {
      return null;
    }
    if (!failed.isEmpty()) {
      int segment = failed.iterator().next();
      failed.remove(segment);
      return Decision.load(segment, true, Decision.DELAY_BACKWARD_FILL);
    }
    int aheadLimit = currentSegment + maxAheadSegments - 1;
    while (nextForwardSegment <= aheadLimit && isScheduled(nextForwardSegment)) {
      nextForwardSegment++;
    }
    if (nextForwardSegment <= Math.min(aheadLimit, totalSegments)) {
      return Decision.load(nextForwardSegment++, false, Decision.DELAY_NONE);
    }
    while (nextBackwardSegment >= 1 && isScheduled(nextBackwardSegment)) {
      nextBackwardSegment--;
    }
    if (nextBackwardSegment >= 1) {
      return Decision.load(
          nextBackwardSegment--,
          false,
          afterSeek ? Decision.DELAY_AFTER_SEEK : Decision.DELAY_BACKWARD_FILL);
    }
    afterSeek = false;
    return nextForwardSegment <= totalSegments ? Decision.checkLater() : null;
  }

  boolean markLoading(int segment) {
    return session != null && !isScheduled(segment) && loading.add(segment);
  }

  void finishFetch(int segment) {
    loading.remove(segment);
  }

  void markFailed(int segment) {
    failed.add(segment);
  }

  boolean markLoaded(int segment) {
    return loaded.add(segment);
  }

  int loadedCount() {
    return loaded.size();
  }

  int totalSegments() {
    return totalSegments;
  }

  boolean isComplete() {
    return session != null && loaded.size() >= totalSegments;
  }

  boolean hasSession() {
    return session != null;
  }

  @Nullable
  Fetcher.Session session() {
    return session;
  }

  void finish(Fetcher.Session session) {
    if (this.session == session) {
      session.release();
      this.session = null;
    }
  }

  void fail(Fetcher.Session session) {
    finish(session);
    loading.clear();
    failed.clear();
    afterSeek = false;
  }

  void release() {
    @Nullable Fetcher.Session session = clear();
    if (session != null) {
      session.release();
    }
  }

  @Nullable
  Fetcher.Session clear() {
    Fetcher.Session clearedSession = session;
    session = null;
    loaded.clear();
    loading.clear();
    failed.clear();
    afterSeek = false;
    return clearedSession;
  }

  private boolean isScheduled(int segment) {
    return loaded.contains(segment) || loading.contains(segment);
  }

  static final class Decision {

    static final int ACTION_LOAD = 0;
    static final int ACTION_CHECK_LATER = 1;
    static final int DELAY_NONE = 0;
    static final int DELAY_BACKWARD_FILL = 1;
    static final int DELAY_AFTER_SEEK = 2;
    final @Action int action;
    final int segment;
    final boolean retry;
    final @Delay int delay;

    private Decision(@Action int action, int segment, boolean retry, @Delay int delay) {
      this.action = action;
      this.segment = segment;
      this.retry = retry;
      this.delay = delay;
    }

    private static Decision load(int segment, boolean retry, @Delay int delay) {
      return new Decision(ACTION_LOAD, segment, retry, delay);
    }

    private static Decision checkLater() {
      return new Decision(ACTION_CHECK_LATER, 0, false, DELAY_NONE);
    }

    @Documented
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef({ACTION_LOAD, ACTION_CHECK_LATER})
    @interface Action {}

    @Documented
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef({DELAY_NONE, DELAY_BACKWARD_FILL, DELAY_AFTER_SEEK})
    @interface Delay {}
  }
}
