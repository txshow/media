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
package androidx.media3.decoder.ffmpeg;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** FFmpeg video decoder with independent input and output backpressure. */
@UnstableApi
final class FfmpegVideoDecoder
    implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  private static final String TAG = "FfmpegVideoDecoder";

  // LINT.IfChange
  private static final int RESULT_SUCCESS = 0;
  private static final int RESULT_TRY_AGAIN = 1;
  private static final int RESULT_END_OF_STREAM = 2;
  private static final int RESULT_INVALID_DATA = -1;

  // LINT.ThenChange(../../../../../jni/ffvideo.cc)

  // LINT.IfChange(decodeLoadLevel)
  /** Decode every frame with normal in-loop filtering. */
  static final int DECODE_LOAD_NORMAL = 0;

  /** Discard non-reference frames before decoding. */
  static final int DECODE_LOAD_NON_REFERENCE = 1;

  /** Also omit in-loop filtering on bidirectional frames while severely late. */
  static final int DECODE_LOAD_AGGRESSIVE = 2;

  /** Decoder load-shedding level selected by {@link FfmpegVideoRecoveryController}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({DECODE_LOAD_NORMAL, DECODE_LOAD_NON_REFERENCE, DECODE_LOAD_AGGRESSIVE})
  @interface DecodeLoadLevel {}

  // LINT.ThenChange(../../../../../jni/ffvideo.cc:decodeLoadLevel)

  private final Object lock;
  private final Object renderLock;
  private final ArrayDeque<DecoderInputBuffer> queuedInputBuffers;
  private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputBuffers;
  private final DecoderInputBuffer[] availableInputBuffers;
  private final VideoDecoderOutputBuffer[] availableOutputBuffers;
  private final Thread decodeThread;
  private final String codecName;
  private final Format format;

  @Nullable private DecoderInputBuffer dequeuedInputBuffer;
  @Nullable private FfmpegDecoderException exception;
  private long nativeContext;
  private long outputStartTimeUs;
  private int availableInputBufferCount;
  private int availableOutputBufferCount;
  private int skippedOutputBufferCount;
  private boolean receivePending;
  private boolean inputEnded;
  private boolean outputEnded;
  private boolean flushPending;
  private boolean released;
  @DecodeLoadLevel private volatile int decodeLoadLevel;
  @C.VideoOutputMode private volatile int outputMode;

  FfmpegVideoDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      Format format)
      throws FfmpegDecoderException {
    checkArgument(numInputBuffers > 0);
    checkArgument(numOutputBuffers > 0);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native library.");
    }
    this.format = format;
    codecName = checkNotNull(FfmpegLibrary.getCodecName(format));
    FfmpegInitializationData initializationData = FfmpegInitializationData.forVideo(format);
    @Nullable ColorInfo colorInfo = format.colorInfo;
    int matrixCoefficients =
        colorInfo == null || colorInfo.colorSpace == Format.NO_VALUE
            ? Format.NO_VALUE
            : ColorInfo.colorSpaceToIsoMatrixCoefficients(colorInfo.colorSpace);
    int colorRange = colorInfo == null ? Format.NO_VALUE : colorInfo.colorRange;
    int colorPrimaries =
        colorInfo == null || colorInfo.colorSpace == Format.NO_VALUE
            ? Format.NO_VALUE
            : ColorInfo.colorSpaceToIsoColorPrimaries(colorInfo.colorSpace);
    int colorTransfer =
        colorInfo == null || colorInfo.colorTransfer == Format.NO_VALUE
            ? Format.NO_VALUE
            : ColorInfo.colorTransferToIsoTransferCharacteristics(colorInfo.colorTransfer);
    nativeContext =
        ffmpegInitialize(
            codecName,
            initializationData.extraData,
            initializationData.dolbyVisionConfig,
            threads,
            format.width,
            format.height,
            FfmpegLibrary.getDolbyVisionOutputMode(format),
            matrixCoefficients,
            colorRange,
            colorPrimaries,
            colorTransfer);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder.");
    }
    boolean initialized = false;
    try {
      lock = new Object();
      renderLock = new Object();
      queuedInputBuffers = new ArrayDeque<>();
      queuedOutputBuffers = new ArrayDeque<>();
      outputStartTimeUs = C.TIME_UNSET;
      outputMode = C.VIDEO_OUTPUT_MODE_NONE;

      availableInputBuffers = new DecoderInputBuffer[numInputBuffers];
      availableInputBufferCount = numInputBuffers;
      int inputBufferPaddingSize = FfmpegLibrary.getInputBufferPaddingSize();
      for (int i = 0; i < numInputBuffers; i++) {
        DecoderInputBuffer inputBuffer =
            new DecoderInputBuffer(
                DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, inputBufferPaddingSize);
        inputBuffer.ensureSpaceForWrite(initialInputBufferSize);
        availableInputBuffers[i] = inputBuffer;
      }

      availableOutputBuffers = new VideoDecoderOutputBuffer[numOutputBuffers];
      availableOutputBufferCount = numOutputBuffers;
      for (int i = 0; i < numOutputBuffers; i++) {
        availableOutputBuffers[i] = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
      }

      decodeThread = new Thread(this::runDecodeLoop, "ExoPlayer:FfmpegVideoDecoder");
      decodeThread.start();
      initialized = true;
    } finally {
      if (!initialized && nativeContext != 0) {
        ffmpegRelease(nativeContext);
        nativeContext = 0;
      }
    }
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  public void setOutputStartTimeUs(long outputStartTimeUs) {
    synchronized (lock) {
      this.outputStartTimeUs = outputStartTimeUs;
    }
  }

  @Nullable
  @Override
  public DecoderInputBuffer dequeueInputBuffer() throws FfmpegDecoderException {
    synchronized (lock) {
      maybeThrowException();
      checkState(dequeuedInputBuffer == null);
      if (availableInputBufferCount == 0 || flushPending || inputEnded) {
        return null;
      }
      dequeuedInputBuffer = availableInputBuffers[--availableInputBufferCount];
      return dequeuedInputBuffer;
    }
  }

  @Override
  public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws FfmpegDecoderException {
    synchronized (lock) {
      maybeThrowException();
      checkArgument(inputBuffer == dequeuedInputBuffer);
      queuedInputBuffers.addLast(inputBuffer);
      dequeuedInputBuffer = null;
      lock.notifyAll();
    }
  }

  @Nullable
  @Override
  public VideoDecoderOutputBuffer dequeueOutputBuffer() throws FfmpegDecoderException {
    synchronized (lock) {
      maybeThrowException();
      return queuedOutputBuffers.isEmpty() || flushPending
          ? null
          : queuedOutputBuffers.removeFirst();
    }
  }

  @Override
  public void flush() {
    synchronized (renderLock) {
      long context;
      synchronized (lock) {
        flushPending = true;
        if (dequeuedInputBuffer != null) {
          releaseInputBufferInternal(dequeuedInputBuffer);
          dequeuedInputBuffer = null;
        }
        context = nativeContext;
        lock.notifyAll();
      }
      if (context != 0) {
        ffmpegFlushSurface(context);
      }
    }
  }

  @Override
  public void release() {
    synchronized (lock) {
      released = true;
      lock.notifyAll();
    }
    boolean wasInterrupted = false;
    while (true) {
      try {
        decodeThread.join();
        break;
      } catch (InterruptedException e) {
        wasInterrupted = true;
      }
    }
    if (wasInterrupted) {
      Thread.currentThread().interrupt();
    }
    synchronized (renderLock) {
      synchronized (lock) {
        releaseQueuedBuffers();
        if (nativeContext != 0) {
          ffmpegRelease(nativeContext);
        }
        nativeContext = 0;
      }
    }
  }

  void setOutputMode(@C.VideoOutputMode int outputMode) {
    checkArgument(
        outputMode == C.VIDEO_OUTPUT_MODE_NONE || outputMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    this.outputMode = outputMode;
  }

  void setDecodeLoadLevel(@DecodeLoadLevel int decodeLoadLevel) {
    this.decodeLoadLevel = decodeLoadLevel;
  }

  boolean renderToSurface(
      VideoDecoderOutputBuffer outputBuffer,
      Surface surface,
      long releaseTimeNs,
      int rotationDegrees)
      throws FfmpegDecoderException {
    synchronized (renderLock) {
      long context;
      synchronized (lock) {
        if (nativeContext == 0 || outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
          throw new FfmpegDecoderException("Invalid output mode or released decoder.");
        }
        context = nativeContext;
      }
      int result;
      TraceUtil.beginSection("ffmpegRenderFrame");
      try {
        result =
            ffmpegRenderFrame(
                context,
                surface,
                outputBuffer.decoderPrivate,
                outputBuffer.width,
                outputBuffer.height,
                releaseTimeNs,
                rotationDegrees);
      } finally {
        TraceUtil.endSection();
      }
      if (result != RESULT_SUCCESS) {
        if (result == RESULT_TRY_AGAIN) {
          return false;
        }
        throw new FfmpegDecoderException("Buffer render error: " + result);
      }
      outputBuffer.decoderPrivate = 0;
      return true;
    }
  }

  void detachOutputSurface() {
    synchronized (renderLock) {
      long context;
      synchronized (lock) {
        if (nativeContext == 0) {
          return;
        }
        context = nativeContext;
      }
      ffmpegDetachSurface(context);
    }
  }

  private void runDecodeLoop() {
    try {
      try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
      } catch (SecurityException e) {
        Log.w(TAG, "Could not raise FFmpeg video decode thread priority.", e);
      }
      while (decodeStep()) {
        // Continue until release or a fatal decoder error.
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException | OutOfMemoryError e) {
      setException(new FfmpegDecoderException("Unexpected decode error", e));
    }
  }

  private boolean decodeStep() throws InterruptedException {
    @Nullable DecoderInputBuffer inputBuffer = null;
    @Nullable VideoDecoderOutputBuffer outputBuffer = null;
    boolean shouldFlush;
    boolean shouldReceive = false;
    synchronized (lock) {
      while (!released && !flushPending && !canDecodeStep()) {
        lock.wait();
      }
      if (released) {
        return false;
      }
      if (flushPending) {
        releaseQueuedBuffers();
        shouldFlush = true;
      } else {
        shouldFlush = false;
        shouldReceive = receivePending;
        if (shouldReceive) {
          outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
        } else {
          inputBuffer = queuedInputBuffers.peekFirst();
        }
      }
    }

    if (shouldFlush) {
      return flushInternal();
    }

    if (shouldReceive) {
      return receiveFrame(checkNotNull(outputBuffer));
    }
    return sendInput(checkNotNull(inputBuffer));
  }

  private boolean sendInput(DecoderInputBuffer inputBuffer) {
    int result;
    TraceUtil.beginSection("ffmpegSendVideoPacket");
    try {
      if (inputBuffer.isEndOfStream()) {
        result = ffmpegSendEndOfStream(nativeContext);
      } else {
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int flags = inputBuffer.isFirstSample() ? C.BUFFER_FLAG_FIRST_SAMPLE : 0;
        result =
            ffmpegSendPacket(
                nativeContext,
                inputData,
                inputData.position(),
                inputData.remaining(),
                inputBuffer.timeUs,
                flags,
                decodeLoadLevel);
      }
    } finally {
      TraceUtil.endSection();
    }

    synchronized (lock) {
      if (flushPending) {
        return true;
      }
      if (result == RESULT_TRY_AGAIN) {
        receivePending = true;
        return true;
      }
      if (result != RESULT_SUCCESS && result != RESULT_INVALID_DATA) {
        setExceptionLocked(new FfmpegDecoderException("ffmpegSendPacket error: " + result));
        return false;
      }
      checkState(queuedInputBuffers.removeFirst() == inputBuffer);
      boolean endOfStream = inputBuffer.isEndOfStream();
      releaseInputBufferInternal(inputBuffer);
      inputEnded |= endOfStream;
      receivePending = true;
      lock.notifyAll();
      return true;
    }
  }

  private boolean receiveFrame(VideoDecoderOutputBuffer outputBuffer) {
    int result;
    TraceUtil.beginSection("ffmpegReceiveVideoFrame");
    try {
      result = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer);
    } finally {
      TraceUtil.endSection();
    }
    synchronized (lock) {
      if (flushPending) {
        releaseOutputBufferInternal(outputBuffer);
        return true;
      }
      if (result == RESULT_SUCCESS) {
        outputBuffer.format = format;
        outputBuffer.shouldBeSkipped = !isAtLeastOutputStartTimeUs(outputBuffer.timeUs);
        queueOrReleaseOutputBuffer(outputBuffer);
        receivePending = true;
      } else if (result == RESULT_TRY_AGAIN) {
        releaseOutputBufferInternal(outputBuffer);
        receivePending = false;
      } else if (result == RESULT_END_OF_STREAM) {
        outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
        skippedOutputBufferCount = 0;
        queuedOutputBuffers.addLast(outputBuffer);
        receivePending = false;
        outputEnded = true;
      } else if (result == RESULT_INVALID_DATA) {
        releaseOutputBufferInternal(outputBuffer);
        receivePending = true;
      } else {
        releaseOutputBufferInternal(outputBuffer);
        setExceptionLocked(new FfmpegDecoderException("ffmpegReceiveFrame error: " + result));
        return false;
      }
      lock.notifyAll();
      return true;
    }
  }

  private void queueOrReleaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    if (outputBuffer.shouldBeSkipped) {
      skippedOutputBufferCount++;
      releaseOutputBufferInternal(outputBuffer);
      return;
    }
    outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
    skippedOutputBufferCount = 0;
    queuedOutputBuffers.addLast(outputBuffer);
  }

  private boolean canDecodeStep() {
    if (outputEnded) {
      return false;
    }
    if (receivePending) {
      return availableOutputBufferCount > 0;
    }
    return !queuedInputBuffers.isEmpty();
  }

  private boolean isAtLeastOutputStartTimeUs(long timeUs) {
    return outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs;
  }

  private void releaseInputBufferInternal(DecoderInputBuffer inputBuffer) {
    inputBuffer.clear();
    availableInputBuffers[availableInputBufferCount++] = inputBuffer;
  }

  private void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    synchronized (renderLock) {
      synchronized (lock) {
        releaseOutputBufferInternal(outputBuffer);
        lock.notifyAll();
      }
    }
  }

  private void releaseOutputBufferInternal(VideoDecoderOutputBuffer outputBuffer) {
    if (outputBuffer.decoderPrivate != 0) {
      ffmpegReleaseFrame(outputBuffer.decoderPrivate);
      outputBuffer.decoderPrivate = 0;
    }
    outputBuffer.clear();
    if (!released) {
      availableOutputBuffers[availableOutputBufferCount++] = outputBuffer;
    }
  }

  private boolean flushInternal() {
    long resetStartTimeMs = SystemClock.elapsedRealtime();
    long resetContext;
    synchronized (renderLock) {
      TraceUtil.beginSection("ffmpegResetVideoDecoder");
      try {
        resetContext = ffmpegReset(nativeContext);
      } finally {
        TraceUtil.endSection();
      }
    }
    long resetDurationMs = SystemClock.elapsedRealtime() - resetStartTimeMs;
    if (resetDurationMs >= 100) {
      Log.w(TAG, "Slow FFmpeg video decoder flush: " + resetDurationMs + " ms.");
    }

    synchronized (lock) {
      if (released) {
        return false;
      }
      if (resetContext == 0) {
        setExceptionLocked(new FfmpegDecoderException("Error resetting decoder."));
        released = true;
        return false;
      }
      nativeContext = resetContext;
      skippedOutputBufferCount = 0;
      receivePending = false;
      inputEnded = false;
      outputEnded = false;
      decodeLoadLevel = DECODE_LOAD_NORMAL;
      flushPending = false;
      lock.notifyAll();
      return true;
    }
  }

  private void releaseQueuedBuffers() {
    if (dequeuedInputBuffer != null) {
      releaseInputBufferInternal(dequeuedInputBuffer);
      dequeuedInputBuffer = null;
    }
    while (!queuedInputBuffers.isEmpty()) {
      releaseInputBufferInternal(queuedInputBuffers.removeFirst());
    }
    while (!queuedOutputBuffers.isEmpty()) {
      releaseOutputBufferInternal(queuedOutputBuffers.removeFirst());
    }
  }

  private void maybeThrowException() throws FfmpegDecoderException {
    if (exception != null) {
      throw exception;
    }
  }

  private void setException(FfmpegDecoderException exception) {
    synchronized (lock) {
      setExceptionLocked(exception);
    }
  }

  private void setExceptionLocked(FfmpegDecoderException exception) {
    this.exception = exception;
    lock.notifyAll();
  }

  private native long ffmpegInitialize(
      String codecName,
      @Nullable byte[] extraData,
      @Nullable byte[] dolbyVisionConfig,
      int threads,
      int width,
      int height,
      int dolbyVisionOutputMode,
      int matrixCoefficients,
      int colorRange,
      int colorPrimaries,
      int colorTransfer);

  private native long ffmpegReset(long context);

  private native void ffmpegRelease(long context);

  private native void ffmpegDetachSurface(long context);

  private native void ffmpegFlushSurface(long context);

  private static native void ffmpegReleaseFrame(long frame);

  private native int ffmpegRenderFrame(
      long context,
      Surface surface,
      long frame,
      int displayedWidth,
      int displayedHeight,
      long releaseTimeNs,
      int rotationDegrees);

  private native int ffmpegSendPacket(
      long context,
      ByteBuffer encodedData,
      int offset,
      int length,
      long inputTimeUs,
      int flags,
      @DecodeLoadLevel int decodeLoadLevel);

  private native int ffmpegSendEndOfStream(long context);

  private native int ffmpegReceiveFrame(
      long context, int outputMode, VideoDecoderOutputBuffer outputBuffer);
}
