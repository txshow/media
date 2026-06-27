/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.offline;

import static androidx.annotation.VisibleForTesting.PRIVATE;
import static androidx.media3.common.util.Util.percentFloat;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.PriorityTaskManager.PriorityTooLowException;
import androidx.media3.common.util.RunnableFutureTask;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** A downloader for progressive media streams. */
@UnstableApi
public final class ProgressiveDownloader implements Downloader {

  private static final long MIN_PARALLEL_CHUNK_SIZE_BYTES = 512 * 1024;

  private final Executor executor;
  private final CacheDataSource.Factory cacheDataSourceFactory;
  private final Cache cache;
  private final String cacheKey;
  private final int parallelDownloadCount;

  @VisibleForTesting(otherwise = PRIVATE)
  /* package */ final DataSpec dataSpec;

  @Nullable private final PriorityTaskManager priorityTaskManager;
  private final ArrayList<DownloadRunnable> activeRunnables;
  private final Object progressLock;
  private final HashMap<Long, Long> bytesCachedByDataSpecPosition;

  @Nullable private ProgressListener progressListener;
  private volatile boolean isCanceled;

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory) {
    this(mediaItem, cacheDataSourceFactory, /* executor= */ Runnable::run);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param position The position of the {@link DataSpec} from which the {@link
   *     ProgressiveDownloader} downloads.
   * @param length The length of the {@link DataSpec} for which the {@link ProgressiveDownloader}
   *     downloads.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem,
      CacheDataSource.Factory cacheDataSourceFactory,
      long position,
      long length) {
    this(mediaItem, cacheDataSourceFactory, /* executor= */ Runnable::run, position, length);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory, Executor executor) {
    this(
        mediaItem,
        cacheDataSourceFactory,
        executor,
        /* position= */ 0,
        /* length= */ C.LENGTH_UNSET);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded.
   * @param position The position of the {@link DataSpec} from which the {@link
   *     ProgressiveDownloader} downloads.
   * @param length The length of the {@link DataSpec} for which the {@link ProgressiveDownloader}
   *     downloads.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem,
      CacheDataSource.Factory cacheDataSourceFactory,
      Executor executor,
      long position,
      long length) {
    this(
        mediaItem,
        cacheDataSourceFactory,
        executor,
        position,
        length,
        /* parallelDownloadCount= */ 1);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded.
   * @param position The position of the {@link DataSpec} from which the {@link
   *     ProgressiveDownloader} downloads.
   * @param length The length of the {@link DataSpec} for which the {@link ProgressiveDownloader}
   *     downloads.
   * @param parallelDownloadCount The maximum number of parallel byte-range downloads to use when
   *     {@code length} is known.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem,
      CacheDataSource.Factory cacheDataSourceFactory,
      Executor executor,
      long position,
      long length,
      int parallelDownloadCount) {
    checkArgument(parallelDownloadCount > 0);
    this.executor = checkNotNull(executor);
    this.cacheDataSourceFactory = checkNotNull(cacheDataSourceFactory);
    checkNotNull(mediaItem.localConfiguration);
    dataSpec =
        new DataSpec.Builder()
            .setUri(mediaItem.localConfiguration.uri)
            .setKey(mediaItem.localConfiguration.customCacheKey)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .setPosition(position)
            .setLength(length)
            .build();
    cache = checkNotNull(cacheDataSourceFactory.getCache());
    cacheKey = cacheDataSourceFactory.getCacheKeyFactory().buildCacheKey(dataSpec);
    this.parallelDownloadCount = parallelDownloadCount;
    priorityTaskManager = cacheDataSourceFactory.getUpstreamPriorityTaskManager();
    activeRunnables = new ArrayList<>();
    progressLock = new Object();
    bytesCachedByDataSpecPosition = new HashMap<>();
  }

  @Override
  public void download(@Nullable ProgressListener progressListener)
      throws IOException, InterruptedException {
    this.progressListener = progressListener;
    synchronized (progressLock) {
      bytesCachedByDataSpecPosition.clear();
    }
    if (priorityTaskManager != null) {
      priorityTaskManager.add(C.PRIORITY_DOWNLOAD);
    }
    try {
      ArrayDeque<DataSpec> pendingDataSpecs = createDownloadDataSpecs();
      while (!isCanceled && (!pendingDataSpecs.isEmpty() || getActiveRunnableCount() > 0)) {
        if (priorityTaskManager != null) {
          priorityTaskManager.proceed(C.PRIORITY_DOWNLOAD);
        }

        while (!pendingDataSpecs.isEmpty()
            && getActiveRunnableCount() < parallelDownloadCount
            && !isCanceled) {
          DownloadRunnable downloadRunnable = new DownloadRunnable(pendingDataSpecs.removeFirst());
          addActiveRunnable(downloadRunnable);
          executor.execute(downloadRunnable);
          downloadRunnable.blockUntilStarted();
        }

        boolean processedRunnable =
            processActiveRunnables(
                pendingDataSpecs, /* blockUntilFinished= */ pendingDataSpecs.isEmpty());
        if (!processedRunnable && getActiveRunnableCount() >= parallelDownloadCount) {
          processActiveRunnable(getActiveRunnable(/* index= */ 0), pendingDataSpecs);
        }
      }
    } finally {
      cancelActiveRunnables();
      waitAndClearActiveRunnables();
      if (priorityTaskManager != null) {
        priorityTaskManager.remove(C.PRIORITY_DOWNLOAD);
      }
    }
  }

  @Override
  public void cancel() {
    synchronized (activeRunnables) {
      isCanceled = true;
      for (int i = 0; i < activeRunnables.size(); i++) {
        activeRunnables.get(i).cancel(/* interruptIfRunning= */ true);
      }
    }
  }

  @Override
  public void remove() {
    cache.removeResource(cacheKey);
  }

  private void onProgress(
      DataSpec progressDataSpec, long contentLength, long bytesCached, long newBytesCached) {
    @Nullable ProgressListener progressListener = this.progressListener;
    if (progressListener == null) {
      return;
    }
    if (dataSpec.length != C.LENGTH_UNSET) {
      contentLength = dataSpec.length;
      synchronized (progressLock) {
        bytesCachedByDataSpecPosition.put(progressDataSpec.position, bytesCached);
        bytesCached = 0;
        for (long bytesCachedInDataSpec : bytesCachedByDataSpecPosition.values()) {
          bytesCached += bytesCachedInDataSpec;
        }
      }
    }
    float percentDownloaded =
        contentLength == C.LENGTH_UNSET || contentLength == 0
            ? C.PERCENTAGE_UNSET
            : percentFloat(bytesCached, contentLength);
    progressListener.onProgress(contentLength, bytesCached, percentDownloaded);
  }

  @VisibleForTesting(otherwise = PRIVATE)
  /* package */ ArrayDeque<DataSpec> createDownloadDataSpecs() {
    ArrayDeque<DataSpec> dataSpecs = new ArrayDeque<>();
    if (dataSpec.length == C.LENGTH_UNSET || dataSpec.length <= 0 || parallelDownloadCount == 1) {
      dataSpecs.add(dataSpec);
      return dataSpecs;
    }

    long chunkCountBySize = ceilDivide(dataSpec.length, MIN_PARALLEL_CHUNK_SIZE_BYTES);
    int chunkCount = (int) min(parallelDownloadCount, max(1, chunkCountBySize));
    long chunkLength = ceilDivide(dataSpec.length, chunkCount);
    for (int i = 0; i < chunkCount; i++) {
      long chunkOffset = i * chunkLength;
      long remainingLength = dataSpec.length - chunkOffset;
      if (remainingLength <= 0) {
        break;
      }
      dataSpecs.add(dataSpec.subrange(chunkOffset, min(chunkLength, remainingLength)));
    }
    return dataSpecs;
  }

  private static long ceilDivide(long dividend, long divisor) {
    return (dividend - 1) / divisor + 1;
  }

  private boolean processActiveRunnables(
      ArrayDeque<DataSpec> pendingDataSpecs, boolean blockUntilFinished)
      throws IOException, InterruptedException {
    boolean processedRunnable = false;
    for (int i = getActiveRunnableCount() - 1; i >= 0; i--) {
      DownloadRunnable downloadRunnable = getActiveRunnable(i);
      if (blockUntilFinished || downloadRunnable.isDone()) {
        processActiveRunnable(downloadRunnable, pendingDataSpecs);
        processedRunnable = true;
      }
    }
    return processedRunnable;
  }

  private void processActiveRunnable(
      DownloadRunnable downloadRunnable, ArrayDeque<DataSpec> pendingDataSpecs)
      throws IOException, InterruptedException {
    try {
      downloadRunnable.get();
    } catch (CancellationException e) {
      if (!isCanceled) {
        throw e;
      }
    } catch (ExecutionException e) {
      Throwable cause = checkNotNull(e.getCause());
      if (cause instanceof PriorityTooLowException) {
        pendingDataSpecs.addFirst(downloadRunnable.dataSpec);
      } else if (cause instanceof IOException) {
        throw (IOException) cause;
      } else {
        // The cause must be an uncaught Throwable type.
        Util.sneakyThrow(cause);
      }
    } finally {
      downloadRunnable.blockUntilFinished();
      removeActiveRunnable(downloadRunnable);
    }
  }

  private void addActiveRunnable(DownloadRunnable downloadRunnable) throws InterruptedException {
    synchronized (activeRunnables) {
      if (isCanceled) {
        throw new InterruptedException();
      }
      activeRunnables.add(downloadRunnable);
    }
  }

  private void removeActiveRunnable(DownloadRunnable downloadRunnable) {
    synchronized (activeRunnables) {
      activeRunnables.remove(downloadRunnable);
    }
  }

  private int getActiveRunnableCount() {
    synchronized (activeRunnables) {
      return activeRunnables.size();
    }
  }

  private DownloadRunnable getActiveRunnable(int index) {
    synchronized (activeRunnables) {
      return activeRunnables.get(index);
    }
  }

  private void cancelActiveRunnables() {
    synchronized (activeRunnables) {
      for (int i = 0; i < activeRunnables.size(); i++) {
        activeRunnables.get(i).cancel(/* interruptIfRunning= */ true);
      }
    }
  }

  private void waitAndClearActiveRunnables() {
    while (true) {
      @Nullable DownloadRunnable downloadRunnable;
      synchronized (activeRunnables) {
        if (activeRunnables.isEmpty()) {
          return;
        }
        downloadRunnable = activeRunnables.get(activeRunnables.size() - 1);
      }
      downloadRunnable.blockUntilFinished();
      removeActiveRunnable(downloadRunnable);
    }
  }

  private final class DownloadRunnable extends RunnableFutureTask<Void, IOException> {
    public final DataSpec dataSpec;
    @Nullable private CacheWriter cacheWriter;

    public DownloadRunnable(DataSpec dataSpec) {
      this.dataSpec = dataSpec;
    }

    @Override
    protected Void doWork() throws IOException {
      cacheWriter =
          new CacheWriter(
              cacheDataSourceFactory.createDataSourceForDownloading(),
              dataSpec,
              /* temporaryBuffer= */ null,
              (contentLength, bytesCached, newBytesCached) ->
                  onProgress(dataSpec, contentLength, bytesCached, newBytesCached));
      cacheWriter.cache();
      return null;
    }

    @Override
    protected void cancelWork() {
      @Nullable CacheWriter cacheWriter = this.cacheWriter;
      if (cacheWriter != null) {
        cacheWriter.cancel();
      }
    }
  }
}
