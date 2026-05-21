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
package androidx.media3.ui.danmaku;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.danmaku.parser.TxtParser;
import java.util.ArrayList;
import java.util.List;

/** Renders danmaku items synchronized to a playback position. */
@MainThread
@UnstableApi
public final class DanmakuView extends View {

  static final long MAX_ACTIVATION_WINDOW_MS = 3000;
  private final List<DanmakuRenderItem> activeItems = new ArrayList<>(200);
  private final List<DanmakuRenderItem> activationScratch = new ArrayList<>(64);
  private final DanmakuTrackManager trackManager = new DanmakuTrackManager();
  private final DanmakuPainter painter;
  private final DanmakuRenderPool renderPool;
  private final PlaybackClock clock = new PlaybackClock();
  private DanmakuConfig config;
  private boolean drawEnabled = true;
  private long lastActivationPositionMs = Long.MIN_VALUE;
  private boolean released;
  private int viewWidth;
  private int viewHeight;

  /** Creates a view. */
  public DanmakuView(Context context) {
    this(context, null);
  }

  /** Creates a view from XML attributes. */
  public DanmakuView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  /** Creates a view from XML attributes and a default style attribute. */
  public DanmakuView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    painter = new DanmakuPainter(getResources().getDisplayMetrics());
    renderPool = new DanmakuRenderPool(painter, this::onItemsAdded);
    config = DanmakuConfig.DEFAULT;
  }

  private static boolean isMeasurementConfigChanged(
      DanmakuConfig oldConfig, DanmakuConfig newConfig) {
    return oldConfig.textSizeSp != newConfig.textSizeSp
        || oldConfig.textScale != newConfig.textScale
        || oldConfig.typeface != newConfig.typeface
        || oldConfig.textBold != newConfig.textBold;
  }

  private static boolean isTrackLayoutConfigChanged(
      DanmakuConfig oldConfig, DanmakuConfig newConfig) {
    return isMeasurementConfigChanged(oldConfig, newConfig)
        || oldConfig.scrollAreaRatio != newConfig.scrollAreaRatio
        || oldConfig.lineSpacing != newConfig.lineSpacing
        || oldConfig.maxScrollLines != newConfig.maxScrollLines
        || oldConfig.maxTopLines != newConfig.maxTopLines
        || oldConfig.maxBottomLines != newConfig.maxBottomLines;
  }

  private static boolean isActiveItemStateConfigChanged(
      DanmakuConfig oldConfig, DanmakuConfig newConfig) {
    return oldConfig.fixedDurationMs != newConfig.fixedDurationMs
        || oldConfig.maxOnScreen != newConfig.maxOnScreen
        || oldConfig.showScroll != newConfig.showScroll
        || oldConfig.showTop != newConfig.showTop
        || oldConfig.showBottom != newConfig.showBottom
        || oldConfig.showReverse != newConfig.showReverse
        || oldConfig.showPositioned != newConfig.showPositioned
        || oldConfig.showSubtitle != newConfig.showSubtitle
        || oldConfig.showSpecial != newConfig.showSpecial;
  }

  private static void deactivate(DanmakuRenderItem danmaku) {
    danmaku.active = false;
    danmaku.trackIndex = -1;
    danmaku.trackSpan = 1;
    danmaku.activatedTimeMs = -1;
  }

  @VisibleForTesting
  static boolean isWithinRetryWindow(long activationMs, long itemTimeMs, long retryWindowMs) {
    return activationMs - itemTimeMs < retryWindowMs;
  }

  /** Returns the current rendering configuration. */
  public DanmakuConfig getConfig() {
    return config;
  }

  /** Sets the rendering configuration. */
  public void setConfig(DanmakuConfig config) {
    checkNotNull(config);
    if (released) {
      return;
    }
    DanmakuConfig oldConfig = this.config;
    boolean offsetChanged = oldConfig.timeOffsetMs != config.timeOffsetMs;
    boolean measurementChanged = isMeasurementConfigChanged(oldConfig, config);
    boolean trackLayoutChanged = isTrackLayoutConfigChanged(oldConfig, config);
    boolean activeItemsChanged =
        trackLayoutChanged || isActiveItemStateConfigChanged(oldConfig, config);
    this.config = config;
    painter.setConfig(config);
    trackManager.setConfig(config);
    if (offsetChanged) {
      clearActiveItems();
      resetActivationCursor(getCurrentPositionMs());
    }
    if (measurementChanged) {
      renderPool.onMeasurementConfigChanged();
      painter.remeasure(activeItems);
    }
    if (trackLayoutChanged) {
      recalculateTracks();
    }
    if (activeItemsChanged) {
      reconcileActiveItemsToTracks();
    }
    requestRedrawForExternalChange();
  }

  /** Adds items to the render pool. */
  public void addItems(List<Danmaku> items) {
    renderPool.add(checkNotNull(items));
  }

  /** Starts rendering from {@code positionMs}. */
  public void start(long positionMs) {
    clock.rebase(positionMs);
    resetActivationCursor(positionMs);
    clock.started = true;
    clock.paused = false;
    clearActiveItems();
    postInvalidateOnAnimation();
  }

  /** Pauses rendering without changing the current position. */
  public void pause() {
    if (!clock.started || clock.paused) {
      return;
    }
    clock.rebase(getCurrentPositionMs());
    clock.paused = true;
  }

  /** Resumes rendering. */
  public void resume() {
    if (!clock.started || !clock.paused) {
      return;
    }
    clock.unpause();
    postInvalidateOnAnimation();
  }

  /** Seeks to {@code positionMs} and clears active items. */
  public void seekTo(long positionMs) {
    clock.rebase(positionMs);
    resetActivationCursor(positionMs);
    clearActiveItems();
    if (clock.started && !clock.paused) {
      postInvalidateOnAnimation();
    }
  }

  /** Re-bases the clock to {@code positionMs} while preserving active item progress. */
  public void syncPosition(long positionMs) {
    long positionDeltaMs = positionMs - getCurrentPositionMs();
    clock.rebase(positionMs);
    for (int i = 0; i < activeItems.size(); i++) {
      activeItems.get(i).activatedTimeMs += positionDeltaMs;
    }
  }

  /** Sets the playback speed used by the render clock. */
  public void setPlaybackSpeed(float speed) {
    if (clock.started && !clock.paused) {
      clock.rebase(getCurrentPositionMs());
    }
    clock.playbackSpeed = Math.max(0.01f, speed);
  }

  /** Stops rendering and clears active items. */
  public void stop() {
    clock.started = false;
    clock.paused = false;
    clearActiveItems();
    invalidate();
  }

  /** Sends text immediately. */
  public void sendNow(String text) {
    checkNotNull(text);
    boolean looksLikeTxt =
        text.length() > 1 && text.charAt(0) == '[' && Character.isDigit(text.charAt(1));
    Danmaku danmaku = looksLikeTxt ? TxtParser.parseLine(text) : null;
    sendNow(danmaku != null ? danmaku : new Danmaku(text, 0));
  }

  /** Sends {@code danmaku} immediately. */
  public void sendNow(Danmaku danmaku) {
    checkNotNull(danmaku);
    if (released || !clock.started || viewWidth <= 0) {
      return;
    }
    DanmakuRenderItem renderItem = new DanmakuRenderItem(danmaku);
    renderItem.measuredWidth = painter.measureWidth(renderItem);
    long currentMs = getCurrentPositionMs();
    if (!trackManager.tryActivate(renderItem, currentMs)) {
      trackManager.forceActivate(renderItem, currentMs);
    }
    if (renderItem.active) {
      activeItems.add(renderItem);
      requestRedrawForExternalChange();
    }
  }

  /** Clears all pooled and active items. */
  public void clear() {
    renderPool.clear();
    clearActiveItems();
    lastActivationPositionMs = Long.MIN_VALUE;
    invalidate();
  }

  /** Replaces the render pool with {@code items}. */
  public void replacePool(List<Danmaku> items) {
    checkNotNull(items);
    if (released) {
      return;
    }
    renderPool.clear();
    clearActiveItems();
    resetActivationCursor(getCurrentPositionMs());
    if (!items.isEmpty()) {
      addItems(items);
    } else {
      requestRedrawForExternalChange();
    }
  }

  /** Releases render resources. */
  public void release() {
    released = true;
    stop();
    renderPool.release();
  }

  /** Returns whether rendering has started. */
  public boolean isStarted() {
    return clock.started;
  }

  /** Returns whether rendering is paused. */
  public boolean isPaused() {
    return clock.paused;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    viewWidth = w;
    viewHeight = h;
    resetActivationCursor(getCurrentPositionMs());
    clearActiveItems();
    recalculateTracks();
    if (clock.started && !clock.paused) {
      postInvalidateOnAnimation();
    }
  }

  private void resetActivationCursor(long positionMs) {
    lastActivationPositionMs = Math.max(Long.MIN_VALUE + 1, positionMs - config.timeOffsetMs - 1);
  }

  private void requestRedrawForExternalChange() {
    if (!clock.started || clock.paused) {
      invalidate();
    } else {
      postInvalidateOnAnimation();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    renderPool.attach();
  }

  @Override
  protected void onDetachedFromWindow() {
    renderPool.detach();
    super.onDetachedFromWindow();
    stop();
  }

  /** Returns whether items are drawn. */
  public boolean isDrawEnabled() {
    return drawEnabled;
  }

  /** Sets whether items are drawn. */
  public void setDrawEnabled(boolean drawEnabled) {
    this.drawEnabled = drawEnabled;
    if (drawEnabled) {
      postInvalidateOnAnimation();
    } else {
      invalidate();
    }
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    if (!clock.started || viewWidth <= 0 || viewHeight <= 0) {
      return;
    }
    long currentMs = getCurrentPositionMs();
    painter.beginFrame();
    if (!clock.paused) {
      activateNewItems(currentMs);
    }
    int activeItemCount = drawActiveItems(canvas, currentMs);
    if (!clock.paused) {
      scheduleNextDraw(currentMs, activeItemCount);
    }
  }

  private int drawActiveItems(Canvas canvas, long currentMs) {
    int writeIdx = 0;
    for (int i = 0; i < activeItems.size(); i++) {
      DanmakuRenderItem item = activeItems.get(i);
      boolean alive =
          painter.draw(drawEnabled ? canvas : null, item, currentMs, viewWidth, viewHeight);
      if (alive) {
        if (writeIdx != i) {
          activeItems.set(writeIdx, item);
        }
        writeIdx++;
      } else {
        deactivate(item);
      }
    }
    if (activeItems.size() > writeIdx) {
      activeItems.subList(writeIdx, activeItems.size()).clear();
    }
    return writeIdx;
  }

  private void scheduleNextDraw(long currentMs, int activeItemCount) {
    if (activeItemCount > 0) {
      postInvalidateOnAnimation();
    } else {
      long nextDelayMs = nextActivationDelayMs(currentMs);
      if (nextDelayMs == 0L) {
        postInvalidateOnAnimation();
      } else if (nextDelayMs > 0L) {
        postInvalidateDelayed(Math.max(16L, nextDelayMs - 16L));
      }
    }
  }

  @VisibleForTesting
  /* package */ long getCurrentPositionMs() {
    return clock.getPositionMs();
  }

  private void activateNewItems(long currentMs) {
    if (activeItems.size() >= config.maxOnScreen) {
      return;
    }
    long activationMs = currentMs - config.timeOffsetMs;
    if (lastActivationPositionMs >= activationMs) {
      return;
    }
    long fromMs = Math.max(lastActivationPositionMs, activationMs - MAX_ACTIVATION_WINDOW_MS);
    collectActivationCandidates(fromMs, activationMs);
    long advanceTo = activateCandidates(currentMs, activationMs);
    activationScratch.clear();
    lastActivationPositionMs = Math.max(lastActivationPositionMs, advanceTo);
  }

  private void collectActivationCandidates(long fromMs, long activationMs) {
    List<DanmakuRenderItem> candidates = activationScratch;
    candidates.clear();
    renderPool.copyRange(fromMs, activationMs, candidates);
    int candidateCount = 0;
    for (int i = 0; i < candidates.size(); i++) {
      DanmakuRenderItem item = candidates.get(i);
      if (!item.active && isTypeVisible(item.type) && isPoolVisible(item.pool)) {
        candidates.set(candidateCount++, item);
      }
    }
    if (candidates.size() > candidateCount) {
      candidates.subList(candidateCount, candidates.size()).clear();
    }
  }

  private long activateCandidates(long currentMs, long activationMs) {
    List<DanmakuRenderItem> candidates = activationScratch;
    long retryWindowMs = Math.max(2000L, config.fixedDurationMs);
    long advanceTo = activationMs;
    for (int i = 0, n = candidates.size(); i < n; i++) {
      DanmakuRenderItem item = candidates.get(i);
      if (activeItems.size() >= config.maxOnScreen) {
        if (isWithinRetryWindow(activationMs, item.timeMs, retryWindowMs)) {
          advanceTo = Math.min(advanceTo, item.timeMs - 1);
        }
        break;
      }
      if (trackManager.tryActivate(item, currentMs)) {
        activeItems.add(item);
      } else if (isWithinRetryWindow(activationMs, item.timeMs, retryWindowMs)) {
        advanceTo = Math.min(advanceTo, item.timeMs - 1);
      }
    }
    return advanceTo;
  }

  private boolean isTypeVisible(@Danmaku.Type int type) {
    switch (type) {
      case Danmaku.TYPE_SCROLL:
        return config.showScroll;
      case Danmaku.TYPE_REVERSE:
        return config.showReverse;
      case Danmaku.TYPE_TOP:
        return config.showTop;
      case Danmaku.TYPE_BOTTOM:
        return config.showBottom;
      case Danmaku.TYPE_POSITIONED:
        return config.showPositioned;
      default:
        return false;
    }
  }

  private boolean isPoolVisible(int pool) {
    switch (pool) {
      case Danmaku.POOL_SUBTITLE:
        return config.showSubtitle;
      case Danmaku.POOL_SPECIAL:
        return config.showSpecial;
      default:
        return true;
    }
  }

  @VisibleForTesting
  /* package */ int getActiveItemCount() {
    return activeItems.size();
  }

  @VisibleForTesting
  /* package */ DanmakuRenderItem getFirstActiveItem() {
    return activeItems.get(0);
  }

  @VisibleForTesting
  /* package */ DanmakuRenderPool getRenderPool() {
    return renderPool;
  }

  private long nextActivationDelayMs(long currentMs) {
    long activationMs = currentMs - config.timeOffsetMs;
    @Nullable Long nextKey = renderPool.nextTimeAfter(activationMs);
    if (nextKey == null) {
      return -1L;
    }
    long delta = nextKey - activationMs;
    return Math.max(0L, (long) (delta / clock.playbackSpeed));
  }

  private void clearActiveItems() {
    for (int i = 0; i < activeItems.size(); i++) {
      deactivate(activeItems.get(i));
    }
    activeItems.clear();
    trackManager.clear();
  }

  private void reconcileActiveItemsToTracks() {
    int writeIdx = 0;
    for (int i = 0; i < activeItems.size(); i++) {
      DanmakuRenderItem item = activeItems.get(i);
      int trackCount = trackManager.trackCount(item.type);
      item.trackSpan = item.type == Danmaku.TYPE_POSITIONED ? 1 : trackManager.resolveSpan(item);
      if (writeIdx < config.maxOnScreen && isActiveItemValid(item, trackCount)) {
        if (writeIdx != i) {
          activeItems.set(writeIdx, item);
        }
        writeIdx++;
      } else {
        deactivate(item);
      }
    }
    if (activeItems.size() > writeIdx) {
      activeItems.subList(writeIdx, activeItems.size()).clear();
    }
    trackManager.clearOccupancy();
    for (int i = 0; i < activeItems.size(); i++) {
      trackManager.record(activeItems.get(i));
    }
  }

  private boolean isActiveItemValid(DanmakuRenderItem item, int trackCount) {
    return isTypeVisible(item.type)
        && isPoolVisible(item.pool)
        && item.trackIndex >= 0
        && item.trackIndex + item.trackSpan <= trackCount;
  }

  private void recalculateTracks() {
    trackManager.update(config, viewWidth, viewHeight, painter.textSizePx(), painter.trackHeight());
  }

  private void onItemsAdded(List<DanmakuRenderItem> items, int generation) {
    if (released || !renderPool.isCurrent(generation)) {
      return;
    }
    if (clock.started) {
      long activationMs = getCurrentPositionMs() - config.timeOffsetMs;
      long windowStartMs = activationMs - MAX_ACTIVATION_WINDOW_MS;
      long earliestDueMs = Long.MAX_VALUE;
      for (int i = 0, size = items.size(); i < size; i++) {
        long timeMs = items.get(i).timeMs;
        if (timeMs > windowStartMs && timeMs <= activationMs) {
          earliestDueMs = Math.min(earliestDueMs, timeMs);
        }
      }
      if (earliestDueMs != Long.MAX_VALUE) {
        lastActivationPositionMs = Math.min(lastActivationPositionMs, earliestDueMs - 1);
      }
      requestRedrawForExternalChange();
    }
  }
}
