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

import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns pending render items and their background text measurement. */
final class DanmakuRenderPool {

  private static final int GENERATION_CHECK_INTERVAL = 32;
  private static final Comparator<DanmakuRenderItem> BY_TIME =
      (first, second) -> Long.compare(first.timeMs, second.timeMs);
  private final Object lock = new Object();
  private final List<TreeMap<Long, List<DanmakuRenderItem>>> poolChunks = new ArrayList<>();
  private final AtomicInteger poolGeneration = new AtomicInteger();
  private final AtomicInteger measurementGeneration = new AtomicInteger();
  private final Object remeasureToken = new Object();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final DanmakuPainter painter;
  private final Listener listener;
  private volatile DanmakuMeasurement currentMeasurement;
  @Nullable private HandlerThread backgroundThread;
  @Nullable private Handler backgroundHandler;
  private boolean detached;
  private boolean released;

  DanmakuRenderPool(DanmakuPainter painter, Listener listener) {
    this.painter = painter;
    this.listener = listener;
    currentMeasurement = painter.measurement(0, 0);
  }

  void add(List<Danmaku> items) {
    if (items.isEmpty() || released) {
      return;
    }
    Danmaku[] sourceItems = items.toArray(new Danmaku[0]);
    int poolVersion = poolGeneration.get();
    postToBackground(() -> createAndMeasure(sourceItems, poolVersion));
  }

  void clear() {
    poolGeneration.incrementAndGet();
    synchronized (lock) {
      poolChunks.clear();
    }
  }

  void onMeasurementConfigChanged() {
    int configGeneration = measurementGeneration.incrementAndGet();
    currentMeasurement = painter.measurement(0, configGeneration);
    synchronized (lock) {
      if (poolChunks.isEmpty()) {
        return;
      }
    }
    remeasure();
  }

  void copyRange(long fromMs, long toMs, List<DanmakuRenderItem> destination) {
    int destinationStart = destination.size();
    boolean multipleChunks;
    synchronized (lock) {
      multipleChunks = poolChunks.size() > 1;
      for (int i = 0; i < poolChunks.size(); i++) {
        NavigableMap<Long, List<DanmakuRenderItem>> range =
            poolChunks.get(i).subMap(fromMs, false, toMs, true);
        for (List<DanmakuRenderItem> bucket : range.values()) {
          destination.addAll(bucket);
        }
      }
    }
    if (multipleChunks && destination.size() - destinationStart > 1) {
      Collections.sort(destination.subList(destinationStart, destination.size()), BY_TIME);
    }
  }

  @Nullable
  Long nextTimeAfter(long timeMs) {
    synchronized (lock) {
      @Nullable Long nextTimeMs = null;
      for (int i = 0; i < poolChunks.size(); i++) {
        @Nullable Long chunkNextTimeMs = poolChunks.get(i).higherKey(timeMs);
        if (chunkNextTimeMs != null && (nextTimeMs == null || chunkNextTimeMs < nextTimeMs)) {
          nextTimeMs = chunkNextTimeMs;
        }
      }
      return nextTimeMs;
    }
  }

  void attach() {
    detached = false;
  }

  void detach() {
    detached = true;
    poolGeneration.incrementAndGet();
    shutdownThread();
  }

  void release() {
    released = true;
    poolGeneration.incrementAndGet();
    synchronized (lock) {
      poolChunks.clear();
    }
    shutdownThread();
  }

  boolean isCurrent(int generation) {
    return poolGeneration.get() == generation;
  }

  private void createAndMeasure(Danmaku[] sourceItems, int poolVersion) {
    if (!isCurrent(poolVersion)) {
      return;
    }
    List<DanmakuRenderItem> renderItems = new ArrayList<>(sourceItems.length);
    for (int i = 0; i < sourceItems.length; i++) {
      if (i % GENERATION_CHECK_INTERVAL == 0 && !isCurrent(poolVersion)) {
        return;
      }
      renderItems.add(new DanmakuRenderItem(sourceItems[i]));
    }
    measureAndAddOnBackground(renderItems, poolVersion);
  }

  private void measureAndAddOnBackground(List<DanmakuRenderItem> items, int poolVersion) {
    while (isCurrent(poolVersion)) {
      DanmakuMeasurement measurement = measurement(poolVersion);
      @Nullable float[] widths = measureWidths(items, measurement);
      if (widths == null) {
        continue;
      }
      TreeMap<Long, List<DanmakuRenderItem>> chunk = buildChunk(items, widths);
      if (commitChunk(chunk, measurement)) {
        mainHandler.post(() -> listener.onItemsAdded(items, measurement.poolGeneration));
        return;
      }
    }
  }

  @Nullable
  private float[] measureWidths(List<DanmakuRenderItem> items, DanmakuMeasurement measurement) {
    if (!isCurrent(measurement)) {
      return null;
    }
    Paint paint = measurement.createPaint();
    float[] widths = new float[items.size()];
    for (int i = 0; i < items.size(); i++) {
      if (i % GENERATION_CHECK_INTERVAL == 0 && !isCurrent(measurement)) {
        return null;
      }
      DanmakuRenderItem item = items.get(i);
      paint.setTextSize(measurement.textSizePx(item));
      widths[i] = paint.measureText(item.text);
    }
    return widths;
  }

  private static TreeMap<Long, List<DanmakuRenderItem>> buildChunk(
      List<DanmakuRenderItem> items, float[] widths) {
    TreeMap<Long, List<DanmakuRenderItem>> chunk = new TreeMap<>();
    for (int i = 0; i < items.size(); i++) {
      DanmakuRenderItem item = items.get(i);
      item.measuredWidth = widths[i];
      List<DanmakuRenderItem> bucket = chunk.get(item.timeMs);
      if (bucket == null) {
        bucket = new ArrayList<>();
        chunk.put(item.timeMs, bucket);
      }
      bucket.add(item);
    }
    return chunk;
  }

  private boolean commitChunk(
      TreeMap<Long, List<DanmakuRenderItem>> chunk, DanmakuMeasurement measurement) {
    synchronized (lock) {
      if (!isCurrent(measurement)) {
        return false;
      }
      poolChunks.add(chunk);
      return true;
    }
  }

  private void remeasure() {
    ensureThread();
    Handler handler = backgroundHandler;
    if (handler == null) {
      return;
    }
    DanmakuMeasurement measurement = measurement(poolGeneration.get());
    handler.removeCallbacksAndMessages(remeasureToken);
    handler.postAtTime(() -> remeasure(measurement), remeasureToken, SystemClock.uptimeMillis());
  }

  private void remeasure(DanmakuMeasurement measurement) {
    @Nullable List<DanmakuRenderItem> snapshot = snapshotItems(measurement);
    if (snapshot == null) {
      return;
    }
    @Nullable float[] widths = measureWidths(snapshot, measurement);
    if (widths == null) {
      return;
    }
    mainHandler.post(() -> applyWidths(snapshot, widths, measurement));
  }

  private void applyWidths(
      List<DanmakuRenderItem> items, float[] widths, DanmakuMeasurement measurement) {
    if (!isCurrent(measurement)) {
      return;
    }
    for (int i = 0; i < items.size(); i++) {
      items.get(i).measuredWidth = widths[i];
    }
  }

  @Nullable
  private List<DanmakuRenderItem> snapshotItems(DanmakuMeasurement measurement) {
    List<TreeMap<Long, List<DanmakuRenderItem>>> chunks;
    synchronized (lock) {
      if (!isCurrent(measurement) || poolChunks.isEmpty()) {
        return null;
      }
      chunks = new ArrayList<>(poolChunks);
    }
    List<DanmakuRenderItem> snapshot = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      for (List<DanmakuRenderItem> bucket : chunks.get(i).values()) {
        snapshot.addAll(bucket);
      }
    }
    return snapshot;
  }

  private DanmakuMeasurement measurement(int poolVersion) {
    return currentMeasurement.withPoolGeneration(poolVersion);
  }

  private boolean isCurrent(DanmakuMeasurement measurement) {
    return poolGeneration.get() == measurement.poolGeneration
        && measurementGeneration.get() == measurement.configGeneration;
  }

  private void ensureThread() {
    if (released || detached || backgroundThread != null) {
      return;
    }
    backgroundThread = new HandlerThread("DanmakuMeasure");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  private void postToBackground(Runnable runnable) {
    ensureThread();
    Handler handler = backgroundHandler;
    if (handler != null) {
      handler.post(runnable);
    }
  }

  private void shutdownThread() {
    if (backgroundThread != null) {
      backgroundThread.quit();
      backgroundThread = null;
      backgroundHandler = null;
    }
  }

  @VisibleForTesting
  /* package */ int size() {
    synchronized (lock) {
      int size = 0;
      for (int i = 0; i < poolChunks.size(); i++) {
        for (List<DanmakuRenderItem> items : poolChunks.get(i).values()) {
          size += items.size();
        }
      }
      return size;
    }
  }

  @VisibleForTesting
  /* package */ DanmakuRenderItem getFirstItem() {
    synchronized (lock) {
      @Nullable DanmakuRenderItem firstItem = null;
      for (int i = 0; i < poolChunks.size(); i++) {
        DanmakuRenderItem chunkFirstItem = poolChunks.get(i).firstEntry().getValue().get(0);
        if (firstItem == null || chunkFirstItem.timeMs < firstItem.timeMs) {
          firstItem = chunkFirstItem;
        }
      }
      if (firstItem == null) {
        throw new IllegalStateException("Pool is empty");
      }
      return firstItem;
    }
  }

  @VisibleForTesting
  @Nullable
  /* package */ HandlerThread getBackgroundThread() {
    return backgroundThread;
  }

  @VisibleForTesting
  @Nullable
  /* package */ Handler getBackgroundHandler() {
    return backgroundHandler;
  }

  interface Listener {

    void onItemsAdded(List<DanmakuRenderItem> items, int generation);
  }
}
