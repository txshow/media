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

import androidx.annotation.Nullable;
import java.util.Arrays;

/** Owns track occupancy and collision decisions for a {@link DanmakuView}. */
final class DanmakuTrackManager {

  private DanmakuConfig config = DanmakuConfig.DEFAULT;
  private DanmakuRenderItem[] scrollTails = new DanmakuRenderItem[0];
  private DanmakuRenderItem[] reverseTails = new DanmakuRenderItem[0];
  private long[] topExpiries = new long[0];
  private long[] bottomExpiries = new long[0];
  private int viewWidth;
  private float textSizePx;
  private float trackHeight;
  private int nextScrollTrack;
  private int nextReverseTrack;
  private int nextTopTrack;
  private int nextBottomTrack;

  private static int configuredCount(int automaticCount, int configuredCount) {
    if (configuredCount <= 0) {
      return Math.max(1, automaticCount);
    }
    return Math.max(1, Math.min(automaticCount, configuredCount));
  }

  private static void activate(
      DanmakuRenderItem item, long currentMs, int trackIndex, int trackSpan) {
    item.trackIndex = trackIndex;
    item.trackSpan = trackSpan;
    item.activatedTimeMs = currentMs;
    item.active = true;
  }

  private static void recordLatest(
      DanmakuRenderItem[] tracks, DanmakuRenderItem item, int start, int end) {
    for (int i = start; i < end; i++) {
      DanmakuRenderItem previous = tracks[i];
      if (previous == null || item.activatedTimeMs > previous.activatedTimeMs) {
        tracks[i] = item;
      }
    }
  }

  void setConfig(DanmakuConfig config) {
    this.config = config;
  }

  void update(
      DanmakuConfig config, int viewWidth, int viewHeight, float textSizePx, float trackHeight) {
    setConfig(config);
    this.viewWidth = viewWidth;
    this.textSizePx = textSizePx;
    this.trackHeight = trackHeight;
    if (viewHeight <= 0 || trackHeight <= 0) {
      return;
    }
    int availableTracks = (int) (viewHeight / trackHeight);
    int scrollTracks = (int) (availableTracks * config.scrollAreaRatio);
    int autoFixedTracks = Math.max(1, availableTracks / 3);
    int scrollTrackCount = configuredCount(scrollTracks, config.maxScrollLines);
    int topTrackCount = configuredCount(autoFixedTracks, config.maxTopLines);
    int bottomTrackCount = configuredCount(autoFixedTracks, config.maxBottomLines);
    if (scrollTails.length != scrollTrackCount) {
      scrollTails = new DanmakuRenderItem[scrollTrackCount];
    }
    if (reverseTails.length != scrollTrackCount) {
      reverseTails = new DanmakuRenderItem[scrollTrackCount];
    }
    if (topExpiries.length != topTrackCount) {
      topExpiries = new long[topTrackCount];
    }
    if (bottomExpiries.length != bottomTrackCount) {
      bottomExpiries = new long[bottomTrackCount];
    }
    clearOccupancy();
    resetIndexes();
  }

  boolean tryActivate(DanmakuRenderItem item, long currentMs) {
    switch (item.type) {
      case Danmaku.TYPE_SCROLL:
        return assignMoving(item, currentMs, false);
      case Danmaku.TYPE_REVERSE:
        return assignMoving(item, currentMs, true);
      case Danmaku.TYPE_TOP:
        return assignFixed(item, currentMs, false);
      case Danmaku.TYPE_BOTTOM:
        return assignFixed(item, currentMs, true);
      case Danmaku.TYPE_POSITIONED:
        activate(item, currentMs, 0, 1);
        return true;
      default:
        return false;
    }
  }

  void forceActivate(DanmakuRenderItem item, long currentMs) {
    switch (item.type) {
      case Danmaku.TYPE_SCROLL:
      case Danmaku.TYPE_REVERSE:
        assignWithoutCollision(item, currentMs, scrollTails.length, false);
        break;
      case Danmaku.TYPE_TOP:
        assignWithoutCollision(item, currentMs, topExpiries.length, false);
        break;
      case Danmaku.TYPE_BOTTOM:
        assignWithoutCollision(item, currentMs, bottomExpiries.length, true);
        break;
      default:
        break;
    }
  }

  int resolveSpan(DanmakuRenderItem item) {
    return resolveSpan(item, trackCount(item.type));
  }

  int trackCount(@Danmaku.Type int type) {
    switch (type) {
      case Danmaku.TYPE_SCROLL:
      case Danmaku.TYPE_REVERSE:
        return scrollTails.length;
      case Danmaku.TYPE_TOP:
        return topExpiries.length;
      case Danmaku.TYPE_BOTTOM:
        return bottomExpiries.length;
      default:
        return Integer.MAX_VALUE;
    }
  }

  void clear() {
    clearOccupancy();
    resetIndexes();
  }

  void clearOccupancy() {
    Arrays.fill(scrollTails, null);
    Arrays.fill(reverseTails, null);
    Arrays.fill(topExpiries, 0);
    Arrays.fill(bottomExpiries, 0);
  }

  void record(DanmakuRenderItem item) {
    int start = item.trackIndex;
    int end = start + item.trackSpan;
    switch (item.type) {
      case Danmaku.TYPE_SCROLL:
        recordLatest(scrollTails, item, start, end);
        break;
      case Danmaku.TYPE_REVERSE:
        recordLatest(reverseTails, item, start, end);
        break;
      case Danmaku.TYPE_TOP:
        recordExpiry(topExpiries, item, start, end);
        break;
      case Danmaku.TYPE_BOTTOM:
        recordExpiry(bottomExpiries, item, start, end);
        break;
      default:
        break;
    }
  }

  private boolean assignMoving(DanmakuRenderItem item, long currentMs, boolean reverse) {
    boolean allowOverlap = reverse ? config.allowReverseOverlap : config.allowScrollOverlap;
    if (allowOverlap) {
      return assignWithoutCollision(item, currentMs, scrollTails.length, false);
    }
    DanmakuRenderItem[] sameDirection = reverse ? reverseTails : scrollTails;
    DanmakuRenderItem[] oppositeDirection = reverse ? scrollTails : reverseTails;
    int span = resolveSpan(item, sameDirection.length);
    for (int i = 0; i + span <= sameDirection.length; i++) {
      if (areMovingTracksAvailable(sameDirection, oppositeDirection, item, currentMs, i, span)) {
        activate(item, currentMs, i, span);
        Arrays.fill(sameDirection, i, i + span, item);
        return true;
      }
    }
    return false;
  }

  private boolean areMovingTracksAvailable(
      DanmakuRenderItem[] sameDirection,
      DanmakuRenderItem[] oppositeDirection,
      DanmakuRenderItem item,
      long currentMs,
      int start,
      int span) {
    for (int i = start; i < start + span; i++) {
      DanmakuRenderItem tail = sameDirection[i];
      if ((tail != null && !canFollow(tail, item, currentMs))
          || isMoving(oppositeDirection[i], currentMs)) {
        return false;
      }
    }
    return true;
  }

  private boolean assignFixed(DanmakuRenderItem item, long currentMs, boolean bottom) {
    boolean allowOverlap = bottom ? config.allowBottomOverlap : config.allowTopOverlap;
    long[] expiries = bottom ? bottomExpiries : topExpiries;
    if (allowOverlap) {
      return assignWithoutCollision(item, currentMs, expiries.length, bottom);
    }
    int span = resolveSpan(item, expiries.length);
    for (int i = 0; i + span <= expiries.length; i++) {
      if (areFixedTracksAvailable(expiries, currentMs, i, span)) {
        activate(item, currentMs, i, span);
        Arrays.fill(expiries, i, i + span, currentMs + config.fixedDurationMs);
        return true;
      }
    }
    return false;
  }

  private static boolean areFixedTracksAvailable(
      long[] expiries, long currentMs, int start, int span) {
    for (int i = start; i < start + span; i++) {
      if (expiries[i] > currentMs) {
        return false;
      }
    }
    return true;
  }

  private boolean assignWithoutCollision(
      DanmakuRenderItem item, long currentMs, int trackCount, boolean bottom) {
    if (trackCount <= 0) {
      return false;
    }
    int span = resolveSpan(item, trackCount);
    int startCount = trackCount - span + 1;
    int track = nextTrack(item, bottom, startCount);
    activate(item, currentMs, track, span);
    record(item);
    return true;
  }

  private int nextTrack(DanmakuRenderItem item, boolean bottom, int startCount) {
    if (item.type == Danmaku.TYPE_TOP) {
      int track = nextTopTrack % startCount;
      nextTopTrack = (track + 1) % startCount;
      return track;
    }
    if (bottom) {
      int track = nextBottomTrack % startCount;
      nextBottomTrack = (track + 1) % startCount;
      return track;
    }
    if (item.type == Danmaku.TYPE_REVERSE) {
      int track = nextReverseTrack % startCount;
      nextReverseTrack = (track + 1) % startCount;
      return track;
    }
    int track = nextScrollTrack % startCount;
    nextScrollTrack = (track + 1) % startCount;
    return track;
  }

  private boolean canFollow(DanmakuRenderItem previous, DanmakuRenderItem next, long currentMs) {
    float previousSpeed =
        (viewWidth + previous.measuredWidth) / (float) effectiveScrollDurationMs();
    long previousElapsed = currentMs - previous.activatedTimeMs;
    float previousRight = viewWidth + previous.measuredWidth - previousSpeed * previousElapsed;
    float minimumGap = textSizePx * config.scrollGapRatio;
    if (previousRight > viewWidth - minimumGap) {
      return false;
    }
    float nextSpeed = (viewWidth + next.measuredWidth) / (float) effectiveScrollDurationMs();
    if (nextSpeed <= previousSpeed) {
      return true;
    }
    float catchUpTime = (viewWidth - previousRight) / (nextSpeed - previousSpeed);
    float previousExitTime = previousRight / previousSpeed;
    return catchUpTime >= previousExitTime;
  }

  private boolean isMoving(@Nullable DanmakuRenderItem item, long currentMs) {
    return item != null
        && item.active
        && currentMs - item.activatedTimeMs < effectiveScrollDurationMs();
  }

  private int resolveSpan(DanmakuRenderItem item, int trackCount) {
    float sizePx = item.textSizeSp > 0 ? scaledTextSize(item.textSizeSp) : textSizePx;
    int span = Math.max(1, (int) Math.ceil(sizePx / trackHeight));
    return Math.min(Math.max(1, trackCount), span);
  }

  private float scaledTextSize(float textSizeSp) {
    return textSizePx * textSizeSp / config.textSizeSp;
  }

  private long effectiveScrollDurationMs() {
    return Math.max(1L, (long) (config.durationMs / Math.max(0.01f, config.scrollSpeedFactor)));
  }

  private void recordExpiry(long[] tracks, DanmakuRenderItem item, int start, int end) {
    long expiry = item.activatedTimeMs + config.fixedDurationMs;
    for (int i = start; i < end; i++) {
      tracks[i] = Math.max(tracks[i], expiry);
    }
  }

  private void resetIndexes() {
    nextScrollTrack = 0;
    nextReverseTrack = 0;
    nextTopTrack = 0;
    nextBottomTrack = 0;
  }
}
