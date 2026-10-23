package androidx.media3.decoder.ffmpeg;

import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoSink;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@link VideoSink} that forwards decoded Dolby Vision profile-5 base-layer frames to the GPU
 * mapping pipeline ({@link FfmpegDolbyVisionP5Native}). Ported from the official obfuscated
 * {@code FfmpegDolbyVisionP5VideoSink} ({@code r8-map-id-c17cc7ab...}) and adapted to the local
 * media3 {@link VideoSink} API.
 */
@UnstableApi
final class FfmpegDolbyVisionP5VideoSink implements VideoSink {

  private static final String TAG = "FfmpegDoviP5Sink";

  private FfmpegDolbyVisionP5Native.NativeRenderer nativeRenderer;
  private @Nullable Surface outputSurface;
  private Size outputResolution = new Size(0, 0);
  private long bufferTimestampAdjustmentUs;
  private boolean initialized;
  private boolean renderingStarted;
  private boolean releaseFirstFrameImmediately;
  private boolean releaseFirstFrameWhenPreviousStreamProcessed;
  private long streamStartPositionUs;
  private boolean endOfInputStreamSignaled;
  private boolean released;

  private long lastStatsPollMs;
  private long firstFrameRenderedMs;
  private int consecutiveFailures;
  private boolean mappingDisabled;
  private boolean firstFrameRenderedNotified;
  private long renderedBaseline;
  private long failuresBaseline;

  private final ArrayDeque<PendingFrame> pendingFrames = new ArrayDeque<>();
  private Listener listener = Listener.NO_OP;
  private Executor executor = Runnable::run;
  private Runnable errorCallback = () -> {};
  private Format inputFormat = new Format.Builder().build();
  private float playbackSpeed = 1.0f;

  /** Queued frame pending release to the GPU pipeline. */
  private static final class PendingFrame {
    public final long bufferTimeUs;
    public final long releaseTimeBaseUs;
    public final VideoFrameHandler frameHandler;

    public PendingFrame(
        long bufferTimeUs, long releaseTimeBaseUs, VideoFrameHandler frameHandler) {
      this.bufferTimeUs = bufferTimeUs;
      this.releaseTimeBaseUs = releaseTimeBaseUs;
      this.frameHandler = frameHandler;
    }
  }

  /** Replaces the runnable fired when the GPU mapping permanently fails. */
  public void setErrorCallback(Runnable errorCallback) {
    this.errorCallback = errorCallback;
  }

  /** Queues an RPU NAL unit for the given buffer timestamp (called by the renderer). */
  public void queueRpu(long bufferTimeUs, byte[] rpu) {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (renderer == null || rpu.length < 3) {
      return;
    }
    synchronized (renderer) {
      long context = renderer.n;
      if (context != 0) {
        FfmpegDolbyVisionP5Native.nativeQueueRpu(context, bufferTimeUs, rpu);
      }
    }
  }

  /** Clears queued frames (called on flush). */
  public void clearQueue() {
    while (!pendingFrames.isEmpty()) {
      PendingFrame frame = pendingFrames.remove();
      frame.frameHandler.skip();
    }
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (renderer != null) {
      synchronized (renderer) {
        if (renderer.n != 0) {
          FfmpegDolbyVisionP5Native.nativeClear(renderer.n);
        }
      }
    }
    endOfInputStreamSignaled = false;
    resetStats();
  }

  @Override
  public void startRendering() {
    renderingStarted = true;
    resetStats();
  }

  @Override
  public void stopRendering() {
    renderingStarted = false;
    resetStats();
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    this.listener = listener;
    this.executor = executor;
  }

  @Override
  public boolean initialize(Format sourceFormat) throws VideoSinkException {
    if (initialized) {
      return true;
    }
    if (mappingDisabled) {
      return false;
    }
    int width = sourceFormat.width;
    int height = sourceFormat.height;
    if (width <= 0
        || height <= 0
        || !FfmpegDolbyVisionP5MappingPolicy.isProfile5Mappable(sourceFormat)
        || sourceFormat.rotationDegrees != 0) {
      throw new VideoSinkException(
          new IllegalArgumentException("Unsupported Dolby Vision profile 5 format"), sourceFormat);
    }
    try {
      FfmpegDolbyVisionP5Native.NativeRenderer renderer =
          FfmpegDolbyVisionP5Native.i(width, height);
      this.nativeRenderer = renderer;
      if (outputSurface != null) {
        renderer.b(outputSurface, outputResolution.getWidth(), outputResolution.getHeight());
      }
      this.inputFormat =
          sourceFormat.buildUpon().setSampleMimeType(MimeTypes.VIDEO_RAW).build();
      this.initialized = true;
      this.endOfInputStreamSignaled = false;
      return true;
    } catch (LinkageError | RuntimeException e) {
      Log.e(TAG, "Failed to initialize Dolby Vision profile 5 GPU mapping", e);
      releaseRenderer();
      if (!mappingDisabled) {
        mappingDisabled = true;
        errorCallback.run();
      }
      return false;
    }
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void redraw() {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (renderer != null) {
      synchronized (renderer) {
        if (renderer.n != 0) {
          FfmpegDolbyVisionP5Native.nativeRedraw(renderer.n);
        }
      }
    }
  }

  @Override
  public void flush(boolean resetPosition) {
    clearQueue();
    if (resetPosition) {
      releaseFirstFrameImmediately = false;
      releaseFirstFrameWhenPreviousStreamProcessed = false;
      firstFrameRenderedNotified = false;
    }
  }

  @Override
  public boolean isReady(boolean otherwiseReady) {
    return initialized && otherwiseReady;
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    releaseFirstFrameImmediately = true;
  }

  @Override
  public void signalEndOfInput() {
    endOfInputStreamSignaled = true;
  }

  @Override
  public boolean isEnded() {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    return endOfInputStreamSignaled
        && pendingFrames.isEmpty()
        && (renderer == null || renderer.a().e == 0);
  }

  @Override
  public Surface getInputSurface() {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (!initialized || renderer == null) {
      Log.e(TAG, "Dolby Vision profile 5 sink is not initialized");
      return null;
    }
    synchronized (renderer) {
      if (renderer.o == null) {
        throw new IllegalStateException("Renderer released");
      }
      return renderer.o;
    }
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    // No-op: the GPU pipeline consumes metadata from the RPU NALs.
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    if (speed > 0f) {
      this.playbackSpeed = speed;
    } else {
      Log.e(TAG, "Playback speed must be positive");
    }
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    // No-op.
  }

  @Override
  public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
    this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    this.outputSurface = outputSurface;
    this.outputResolution = outputResolution;
    this.firstFrameRenderedNotified = false;
    resetStats();
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (renderer != null) {
      renderer.b(outputSurface, outputResolution.getWidth(), outputResolution.getHeight());
    }
  }

  @Override
  public void clearOutputSurfaceInfo() {
    this.outputSurface = null;
    this.outputResolution = new Size(0, 0);
    this.firstFrameRenderedNotified = false;
    resetStats();
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    if (renderer != null) {
      renderer.b(null, 0, 0);
    }
  }

  @Override
  public void setChangeFrameRateStrategy(
      @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
    // No-op.
  }

  @Override
  public void onInputStreamChanged(
      @InputType int inputType,
      Format format,
      long startPositionUs,
      @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
      List<Effect> videoEffects) {
    this.inputFormat = format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_RAW).build();
    this.streamStartPositionUs = startPositionUs;
    this.releaseFirstFrameImmediately = firstFrameReleaseInstruction == RELEASE_FIRST_FRAME_IMMEDIATELY;
    this.releaseFirstFrameWhenPreviousStreamProcessed =
        firstFrameReleaseInstruction == RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
    this.endOfInputStreamSignaled = false;
    this.executor.execute(
        () -> listener.onVideoSizeChanged(new VideoSize(format.width, format.height)));
  }

  @Override
  public void allowReleaseFirstFrameBeforeStarted() {
    this.releaseFirstFrameImmediately = true;
  }

  @Override
  public boolean handleInputFrame(long bufferPresentationTimeUs, VideoFrameHandler frameHandler) {
    if (initialized && !released && pendingFrames.size() < 8) {
      pendingFrames.add(
          new PendingFrame(
              bufferPresentationTimeUs,
              bufferPresentationTimeUs + bufferTimestampAdjustmentUs,
              frameHandler));
      return true;
    }
    return false;
  }

  @Override
  public boolean handleInputBitmap(
      android.graphics.Bitmap inputBitmap,
      androidx.media3.common.util.TimestampIterator bufferTimestampIterator) {
    return false;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    Surface surface = this.outputSurface;
    if (renderer == null || surface == null || !surface.isValid()) {
      lastStatsPollMs = Long.MIN_VALUE;
      firstFrameRenderedMs = Long.MIN_VALUE;
    } else {
      long nowMs = SystemClock.elapsedRealtime();
      if (lastStatsPollMs == Long.MIN_VALUE || nowMs - lastStatsPollMs >= 100) {
        lastStatsPollMs = nowMs;
        FfmpegDolbyVisionP5Native.Stats stats = renderer.a();
        long renderedDelta = Math.max(0L, stats.j - renderedBaseline);
        long failuresDelta = Math.max(0L, stats.k - failuresBaseline);
        renderedBaseline = stats.j;
        failuresBaseline = stats.k;
        if (renderedDelta > 0) {
          consecutiveFailures = 0;
          firstFrameRenderedMs = stats.e > 0 ? nowMs : Long.MIN_VALUE;
          if (!firstFrameRenderedNotified) {
            firstFrameRenderedNotified = true;
            executor.execute(listener::onFirstFrameRendered);
          }
        } else {
          consecutiveFailures = (int) Math.min(3L, ((long) consecutiveFailures) + failuresDelta);
          if (stats.e == 0) {
            firstFrameRenderedMs = Long.MIN_VALUE;
          } else if (firstFrameRenderedMs == Long.MIN_VALUE) {
            firstFrameRenderedMs = nowMs;
          }
        }
        long pending = stats.e;
        long firstFrameAt = firstFrameRenderedMs;
        boolean stuck =
            pending > 0
                && firstFrameAt != Long.MIN_VALUE
                && nowMs - firstFrameAt >= 2000;
        if ((consecutiveFailures >= 3 || stuck) && !mappingDisabled) {
          Log.w(TAG, "Disabling failed Dolby Vision profile 5 GPU mapping: " + stats);
          mappingDisabled = true;
          errorCallback.run();
        }
      }
    }

    PendingFrame pendingFrame = pendingFrames.peek();
    if (pendingFrame == null) {
      return;
    }
    long earlyUs =
        (long) ((pendingFrame.releaseTimeBaseUs - (positionUs + bufferTimestampAdjustmentUs))
            / (double) playbackSpeed);
    if (renderingStarted) {
      earlyUs -= SystemClock.elapsedRealtime() * 1000 - elapsedRealtimeUs;
    }
    boolean canRelease =
        releaseFirstFrameImmediately
            || (releaseFirstFrameWhenPreviousStreamProcessed && positionUs >= streamStartPositionUs);
    boolean actionable = renderingStarted || canRelease;
    if (!actionable || earlyUs > 50000) {
      return;
    }
    pendingFrames.remove();
    if (earlyUs < -30000) {
      pendingFrame.frameHandler.skip();
      executor.execute(listener::onFrameDropped);
      return;
    }
    long releaseNs = Math.max(0L, earlyUs) * 1000 + System.nanoTime();
    boolean queued = false;
    FfmpegDolbyVisionP5Native.NativeRenderer renderer2 = this.nativeRenderer;
    if (renderer2 != null) {
      long context = renderer2.n;
      synchronized (renderer2) {
        queued = context != 0
            && FfmpegDolbyVisionP5Native.nativeQueueFrame(context, releaseNs, pendingFrame.bufferTimeUs);
      }
    }
    if (queued) {
      pendingFrame.frameHandler.render(releaseNs);
      releaseFirstFrameImmediately = false;
      releaseFirstFrameWhenPreviousStreamProcessed = false;
    } else {
      pendingFrame.frameHandler.skip();
      executor.execute(listener::onFrameDropped);
    }
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    // No-op.
  }

  @Override
  public void release() {
    if (released) {
      return;
    }
    released = true;
    releaseRenderer();
    outputSurface = null;
    outputResolution = new Size(0, 0);
  }

  private void releaseRenderer() {
    while (!pendingFrames.isEmpty()) {
      try {
        pendingFrames.remove().frameHandler.skip();
      } catch (Throwable t) {
        pendingFrames.clear();
        releaseRendererInternal();
        throw t;
      }
    }
    pendingFrames.clear();
    releaseRendererInternal();
  }

  private void releaseRendererInternal() {
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    this.nativeRenderer = null;
    initialized = false;
    renderingStarted = false;
    releaseFirstFrameImmediately = false;
    releaseFirstFrameWhenPreviousStreamProcessed = false;
    endOfInputStreamSignaled = false;
    mappingDisabled = false;
    firstFrameRenderedNotified = false;
    resetStats();
    if (renderer != null) {
      renderer.close();
    }
  }

  private void resetStats() {
    consecutiveFailures = 0;
    lastStatsPollMs = Long.MIN_VALUE;
    firstFrameRenderedMs = Long.MIN_VALUE;
    FfmpegDolbyVisionP5Native.NativeRenderer renderer = this.nativeRenderer;
    FfmpegDolbyVisionP5Native.Stats stats =
        renderer == null ? FfmpegDolbyVisionP5Native.Stats.l : renderer.a();
    renderedBaseline = stats.j;
    failuresBaseline = stats.k;
  }
}
