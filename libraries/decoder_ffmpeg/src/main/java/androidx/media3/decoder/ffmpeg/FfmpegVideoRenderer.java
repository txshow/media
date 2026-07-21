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

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Objects;

/** Decodes and renders video using FFmpeg. */
@UnstableApi
public final class FfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "FfmpegVideoRenderer";
  // Zero lets FFmpeg choose an affinity-aware thread count and add one worker for load balancing.
  private static final int DEFAULT_THREAD_COUNT = 0;
  private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;

  private final int threads;
  private final int numInputBuffers;
  private final int numOutputBuffers;
  private final FfmpegVideoRecoveryController recoveryController;

  @Nullable private FfmpegVideoDecoder decoder;
  @Nullable private Surface lastOutputSurface;
  @FfmpegVideoDecoder.DecodeLoadLevel private int decodeLoadLevel;

  /**
   * Creates a new instance without display-aware frame release timing.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public FfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        /* context= */ null,
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        DEFAULT_THREAD_COUNT,
        DEFAULT_NUM_OF_INPUT_BUFFERS,
        DEFAULT_NUM_OF_OUTPUT_BUFFERS);
  }

  /**
   * Creates a new instance.
   *
   * @param context A context from which display information can be retrieved.
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public FfmpegVideoRenderer(
      Context context,
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        context,
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        DEFAULT_THREAD_COUNT,
        DEFAULT_NUM_OF_INPUT_BUFFERS,
        DEFAULT_NUM_OF_OUTPUT_BUFFERS);
  }

  private FfmpegVideoRenderer(
      @Nullable Context context,
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int numInputBuffers,
      int numOutputBuffers) {
    super(context, allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = threads;
    this.numInputBuffers = numInputBuffers;
    this.numOutputBuffers = numOutputBuffers;
    recoveryController = new FfmpegVideoRecoveryController();
  }

  private static boolean isCommonVideoCodec(String mimeType) {
    return MimeTypes.VIDEO_H264.equals(mimeType)
        || MimeTypes.VIDEO_H265.equals(mimeType)
        || MimeTypes.VIDEO_H266.equals(mimeType)
        || MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)
        || MimeTypes.VIDEO_AV1.equals(mimeType)
        || MimeTypes.VIDEO_APV.equals(mimeType)
        || MimeTypes.VIDEO_VP8.equals(mimeType)
        || MimeTypes.VIDEO_VP9.equals(mimeType);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public void handleMessage(@MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    if (messageType != MSG_SET_VIDEO_OUTPUT) {
      super.handleMessage(messageType, message);
      return;
    }
    if (message != null && !(message instanceof Surface)) {
      throw createRendererException(
          new FfmpegDecoderException("FFmpeg video output requires a Surface."),
          /* format= */ null,
          PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);
    }
    @Nullable Surface newOutputSurface = message instanceof Surface ? (Surface) message : null;
    @Nullable Surface previousOutputSurface = lastOutputSurface;
    super.handleMessage(messageType, message);
    if (decoder != null
        && previousOutputSurface != null
        && previousOutputSurface != newOutputSurface) {
      decoder.detachOutputSurface();
    }
    lastOutputSurface = newOutputSurface;
  }

  @Override
  @Capabilities
  public int supportsFormat(Format format) {
    String mimeType = format.sampleMimeType;
    if (!FfmpegLibrary.isAvailable() || mimeType == null || !MimeTypes.isVideo(mimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    } else if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    } else if (!FfmpegLibrary.supportsVideoOutput(format)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    @DecoderSupport
    int decoderSupport =
        isCommonVideoCodec(mimeType) ? DECODER_SUPPORT_FALLBACK : DECODER_SUPPORT_PRIMARY;
    return RendererCapabilities.create(
        C.FORMAT_HANDLED,
        ADAPTIVE_NOT_SEAMLESS,
        TUNNELING_NOT_SUPPORTED,
        HARDWARE_ACCELERATION_NOT_SUPPORTED,
        decoderSupport);
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (!renderOutputBufferToSurfaceIfReady(
        outputBuffer,
        surface,
        outputBuffer.timeUs,
        System.nanoTime(),
        checkNotNull(outputBuffer.format))) {
      throw new FfmpegDecoderException("FFmpeg Surface render queue is temporarily full.");
    }
  }

  @Override
  protected boolean renderOutputBufferToSurfaceIfReady(
      VideoDecoderOutputBuffer outputBuffer,
      Surface surface,
      long presentationTimeUs,
      long releaseTimeNs,
      Format outputFormat)
      throws FfmpegDecoderException {
    if (decoder == null) {
      throw new FfmpegDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    boolean accepted;
    try {
      accepted =
          decoder.renderToSurface(
              outputBuffer, surface, releaseTimeNs, outputFormat.rotationDegrees);
    } catch (FfmpegDecoderException e) {
      outputBuffer.release();
      throw e;
    }
    if (accepted) {
      outputBuffer.release();
    }
    return accepted;
  }

  @Override
  protected VideoSize getOutputVideoSize(int width, int height, Format outputFormat) {
    float pixelWidthHeightRatio = outputFormat.pixelWidthHeightRatio;
    if (outputFormat.rotationDegrees == 90 || outputFormat.rotationDegrees == 270) {
      return new VideoSize(height, width, 1 / pixelWidthHeightRatio);
    }
    return new VideoSize(width, height, pixelWidthHeightRatio);
  }

  @Override
  protected Decoder<
          DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
      createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) throws DecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    try {
      int initialInputBufferSize =
          format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
      FfmpegVideoDecoder decoder =
          new FfmpegVideoDecoder(
              numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, format);
      decoder.setDecodeLoadLevel(decodeLoadLevel);
      this.decoder = decoder;
      return decoder;
    } finally {
      TraceUtil.endSection();
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
    updateDecoderLoadLevel(earlyUs, elapsedRealtimeUs);
    return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs);
  }

  @Override
  protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
    updateDecoderLoadLevel(earlyUs, elapsedRealtimeUs);
    if (!recoveryController.shouldRequestKeyframeResync(
        super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs))) {
      return false;
    }
    return true;
  }

  @Override
  protected void flushDecoder() throws ExoPlaybackException {
    recoveryController.onDecoderFlushed();
    setDecoderLoadLevel(FfmpegVideoDecoder.DECODE_LOAD_NORMAL);
    super.flushDecoder();
  }

  @Override
  protected void onPositionReset(
      long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
      throws ExoPlaybackException {
    recoveryController.reset();
    super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
  }

  private void updateDecoderLoadLevel(long earlyUs, long elapsedRealtimeUs) {
    setDecoderLoadLevel(recoveryController.updateDecodeLoadLevel(earlyUs, elapsedRealtimeUs));
  }

  private void setDecoderLoadLevel(@FfmpegVideoDecoder.DecodeLoadLevel int decodeLoadLevel) {
    if (this.decodeLoadLevel == decodeLoadLevel) {
      return;
    }
    this.decodeLoadLevel = decodeLoadLevel;
    if (decoder != null) {
      decoder.setDecodeLoadLevel(decodeLoadLevel);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    int discardReasons = 0;
    if (!Objects.equals(
        FfmpegLibrary.getCodecName(oldFormat), FfmpegLibrary.getCodecName(newFormat))) {
      discardReasons |= DISCARD_REASON_MIME_TYPE_CHANGED;
    }
    if (!oldFormat.initializationDataEquals(newFormat)) {
      discardReasons |= DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
    }
    if (oldFormat.width != newFormat.width || oldFormat.height != newFormat.height) {
      discardReasons |= DISCARD_REASON_VIDEO_RESOLUTION_CHANGED;
    }
    if (!Objects.equals(oldFormat.colorInfo, newFormat.colorInfo)
        || FfmpegLibrary.getDolbyVisionOutputMode(oldFormat)
            != FfmpegLibrary.getDolbyVisionOutputMode(newFormat)) {
      discardReasons |= DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED;
    }
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        discardReasons == 0 ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : REUSE_RESULT_NO,
        discardReasons);
  }

  @Override
  protected void releaseDecoder() {
    try {
      super.releaseDecoder();
    } finally {
      decoder = null;
      recoveryController.reset();
      decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_NORMAL;
    }
  }
}
