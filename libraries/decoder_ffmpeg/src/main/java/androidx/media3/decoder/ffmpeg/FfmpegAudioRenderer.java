/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_ENCODING_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioSink.SinkFormatSupport;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import java.util.Objects;

/** Decodes and renders audio using FFmpeg. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

  private static final String TAG = "FfmpegAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

  /**
   * @deprecated Use {@link #FfmpegAudioRenderer(Context)} instead.
   */
  @SuppressWarnings("deprecation") // Calling deprecated constructor.
  @Deprecated
  public FfmpegAudioRenderer() {
    this(/* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param context A context.
   */
  public FfmpegAudioRenderer(Context context) {
    this(context, /* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   * @deprecated Use {@link #FfmpegAudioRenderer(Context, Handler, AudioRendererEventListener,
   *     AudioProcessor...)} instead.
   */
  @SuppressWarnings("deprecation") // Calling deprecated constructor.
  @Deprecated
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param context A context.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public FfmpegAudioRenderer(
      Context context,
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder(context).setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    } else if (!FfmpegLibrary.supportsFormat(format)
        || (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)
            && !sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT))) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    } else if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return C.FORMAT_UNSUPPORTED_DRM;
    } else {
      return C.FORMAT_HANDLED;
    }
  }

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegAudioDecoder");
    try {
      int initialInputBufferSize =
          format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
      return new FfmpegAudioDecoder(
          format, NUM_BUFFERS, NUM_BUFFERS, initialInputBufferSize, shouldOutputFloat(format));
    } finally {
      TraceUtil.endSection();
    }
  }

  @Override
  protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(decoder.getChannelCount())
        .setSampleRate(decoder.getSampleRate())
        .setPcmEncoding(decoder.getEncoding())
        .build();
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
    if (oldFormat.channelCount == Format.NO_VALUE
        || newFormat.channelCount == Format.NO_VALUE
        || oldFormat.channelCount != newFormat.channelCount) {
      discardReasons |= DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED;
    }
    if (oldFormat.sampleRate == Format.NO_VALUE
        || newFormat.sampleRate == Format.NO_VALUE
        || oldFormat.sampleRate != newFormat.sampleRate) {
      discardReasons |= DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED;
    }
    if (shouldOutputFloat(oldFormat) != shouldOutputFloat(newFormat)) {
      discardReasons |= DISCARD_REASON_AUDIO_ENCODING_CHANGED;
    }
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        discardReasons == 0 ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : REUSE_RESULT_NO,
        discardReasons);
  }

  /**
   * Returns whether the renderer's {@link AudioSink} supports the PCM format that will be output
   * from the decoder for the given input format and requested output encoding.
   */
  private boolean sinkSupportsFormat(Format inputFormat, @C.PcmEncoding int pcmEncoding) {
    return sinkSupportsFormat(getPcmOutputFormat(inputFormat, pcmEncoding));
  }

  private boolean shouldOutputFloat(Format inputFormat) {
    if (!sinkSupportsFormat(inputFormat, C.ENCODING_PCM_16BIT)) {
      // We have no choice because the sink doesn't support 16-bit integer PCM.
      return true;
    }

    @SinkFormatSupport
    int formatSupport = getSinkFormatSupport(getPcmOutputFormat(inputFormat, C.ENCODING_PCM_FLOAT));
    switch (formatSupport) {
      case SINK_FORMAT_SUPPORTED_DIRECTLY:
        // AC-3 is always 16-bit, so there's no point using floating point. Assume that it's worth
        // using for all other formats.
        return !MimeTypes.AUDIO_AC3.equals(inputFormat.sampleMimeType);
      case SINK_FORMAT_UNSUPPORTED:
      case SINK_FORMAT_SUPPORTED_WITH_TRANSCODING:
      default:
        // Always prefer 16-bit PCM if the sink does not provide direct support for floating point.
        return false;
    }
  }

  private static Format getPcmOutputFormat(Format inputFormat, @C.PcmEncoding int pcmEncoding) {
    return Util.getPcmFormat(
        pcmEncoding, inputFormat.channelCount, FfmpegLibrary.getPcmOutputSampleRate(inputFormat));
  }
}
