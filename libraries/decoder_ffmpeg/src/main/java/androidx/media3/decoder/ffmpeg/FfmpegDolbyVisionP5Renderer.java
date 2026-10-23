package androidx.media3.decoder.ffmpeg;

import android.content.Context;
import android.media.MediaCrypto;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Video renderer that plays Dolby Vision profile-5 streams by decoding the compatible base layer
 * with MediaCodec while RPU metadata is mapped onto the GPU pipeline ({@link
 * FfmpegDolbyVisionP5VideoSink}). Ported from the official obfuscated {@code
 * FfmpegDolbyVisionP5Renderer} ({@code r8-map-id-c17cc7ab...}).
 */
@UnstableApi
public final class FfmpegDolbyVisionP5Renderer extends MediaCodecVideoRenderer {

  private static final String TAG = "FfmpegDoviP5Renderer";

  private final Context context;
  private final FfmpegDolbyVisionP5VideoSink sink;
  private volatile boolean mappingDisabled;

  public FfmpegDolbyVisionP5Renderer(
      Context context,
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      long allowedJoiningTimeMs,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        new FfmpegDolbyVisionP5VideoSink(),
        context,
        codecAdapterFactory,
        mediaCodecSelector,
        allowedJoiningTimeMs,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify);
  }

  private FfmpegDolbyVisionP5Renderer(
      FfmpegDolbyVisionP5VideoSink sink,
      Context context,
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      long allowedJoiningTimeMs,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    super(
        new MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setVideoSink(sink));
    this.context = context.getApplicationContext();
    this.sink = sink;
    sink.setErrorCallback(() -> mappingDisabled = true);
  }

  /** Strips the Dolby Vision stream down to its HEVC base-layer format for MediaCodec. */
  private static Format toBaseLayerFormat(Format format) {
    @Nullable
    byte[] doviCsd = androidx.media3.common.util.CodecSpecificDataUtil.getDolbyVisionCsd(format);
    ArrayList<byte[]> initializationData = new ArrayList<>(format.initializationData);
    if (doviCsd != null && initializationData.size() > 2) {
      initializationData.remove(2);
    }
    return format
        .buildUpon()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(null)
        .setInitializationData(initializationData)
        .build();
  }

  /** Returns the offset of the next NAL start code (0x000001 or 0x00000001), or -1. */
  private static int findNalStart(ByteBuffer data, int from, int limit) {
    while (true) {
      int scan = from + 3;
      if (scan > limit) {
        return -1;
      }
      if (data.get(from) == 0 && data.get(from + 1) == 0) {
        int startCodeOffset = from + 2;
        if (data.get(startCodeOffset) == 1
            || (from + 4 <= limit
                && data.get(startCodeOffset) == 0
                && data.get(scan) == 1)) {
          return from;
        }
      }
      from++;
    }
  }

  private boolean isDoviP5Supported(MediaCodecSelector selector, Format format)
      throws DecoderQueryException {
    if (mappingDisabled
        || format.rotationDegrees != 0
        || !FfmpegDolbyVisionP5MappingPolicy.isProfile5Mappable(format)) {
      return false;
    }
    // The GPU pipeline must be available.
    return FfmpegDolbyVisionP5Native.probe().a;
  }

  private List<MediaCodecInfo> getHardwareDecoderInfos(
      MediaCodecSelector selector, Format format) throws DecoderQueryException {
    List<MediaCodecInfo> all =
        getDecoderInfos(selector, toBaseLayerFormat(format), /* requiresSecureDecoder= */ false);
    ArrayList<MediaCodecInfo> hardware = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      MediaCodecInfo info = all.get(i);
      if (info.hardwareAccelerated) {
        hardware.add(info);
      }
    }
    return hardware;
  }

  @Override
  protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
    super.onQueueInputBuffer(buffer);
    ByteBuffer data = buffer.data;
    if (data == null) {
      return;
    }
    ByteBuffer duplicate = data.duplicate();
    int position = duplicate.position();
    int limit = duplicate.limit();
    List<byte[]> rpus = extractRpus(duplicate, position, limit);
    for (int i = 0; i < rpus.size(); i++) {
      sink.queueRpu(buffer.timeUs, rpus.get(i));
    }
  }

  /** Extracts the Dolby Vision RPU NAL units (type 62) from an HEVC bitstream buffer. */
  private static List<byte[]> extractRpus(ByteBuffer data, int position, int limit) {
    // Try length-prefixed (AVCC) framing first, then fall back to start codes.
    List<byte[]> rpus = Collections.emptyList();
    int offset = position;
    while (limit - offset >= 4) {
      long length = 0;
      for (int i = 0; i < 4; i++) {
        length = (length << 8) | (((long) data.get(offset + i)) & 255);
      }
      int headerEnd = offset + 4;
      if (length < 2 || length > limit - headerEnd) {
        break;
      }
      if (((data.get(headerEnd) & 126) >> 1) == 62) {
        if (rpus.isEmpty()) {
          rpus = new ArrayList<>();
        }
        byte[] rpu = new byte[(int) length];
        ByteBuffer slice = data.duplicate();
        slice.position(headerEnd);
        slice.limit((int) (headerEnd + length));
        slice.get(rpu);
        rpus.add(rpu);
      }
      offset = (int) (headerEnd + length);
    }
    if (offset == position) {
      // Start-coded (Annex B) framing.
      int start = findNalStart(data, position, limit);
      if (start >= 0) {
        rpus = new ArrayList<>();
        int nalOffset = start;
        while (true) {
          int nextStart = findNalStart(data, nalOffset, limit);
          if (nextStart < 0) {
            break;
          }
          int header = nextStart + (data.get(nextStart + 2) == 1 ? 3 : 4);
          int nextNal = findNalStart(data, header, limit);
          int end = nextNal < 0 ? limit : nextNal;
          int length = end - header;
          if (length >= 3 && ((data.get(header) & 126) >> 1) == 62) {
            byte[] rpu = new byte[length];
            ByteBuffer slice = data.duplicate();
            slice.position(header);
            slice.limit(end);
            slice.get(rpu);
            rpus.add(rpu);
          }
          if (nextNal < 0) {
            break;
          }
          nalOffset = nextNal;
        }
      }
    }
    return rpus;
  }

  @Override
  protected void resetCodecStateForFlush() {
    super.resetCodecStateForFlush();
    sink.clearQueue();
  }

  @Override
  public String getName() {
    return "FfmpegDolbyVisionP5Renderer";
  }

  @Override
  protected @C.FormatSupport int supportsFormat(
      MediaCodecSelector mediaCodecSelector, Format format) throws DecoderQueryException {
    if (!isDoviP5Supported(mediaCodecSelector, format)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }
    List<MediaCodecInfo> hardwareDecoders = getHardwareDecoderInfos(mediaCodecSelector, format);
    if (hardwareDecoders.isEmpty()) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    Format adapted = toBaseLayerFormat(format);
    for (int i = 0; i < hardwareDecoders.size(); i++) {
      if (hardwareDecoders.get(i).isFormatFunctionallySupported(context, adapted)) {
        return C.FORMAT_HANDLED;
      }
    }
    return C.FORMAT_UNSUPPORTED_SUBTYPE;
  }

  @Override
  protected List<MediaCodecInfo> getDecoderInfos(
      MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder)
      throws DecoderQueryException {
    if (requiresSecureDecoder || !isDoviP5Supported(mediaCodecSelector, format)) {
      return Collections.emptyList();
    }
    List<MediaCodecInfo> hardwareDecoders = getHardwareDecoderInfos(mediaCodecSelector, format);
    if (!hardwareDecoders.isEmpty()) {
      return hardwareDecoders;
    }
    return Collections.emptyList();
  }

  @Override
  protected void onDisabled() {
    try {
      super.onDisabled();
    } finally {
      sink.release();
    }
  }

  @Override
  protected void onRelease() {
    try {
      super.onRelease();
    } finally {
      sink.release();
    }
  }
}
