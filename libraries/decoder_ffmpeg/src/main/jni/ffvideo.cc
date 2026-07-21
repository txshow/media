#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>

#include "ffcommon.h"
#include "ffvideo_surface.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/buffer.h>
#include <libavutil/dict.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/pixdesc.h>
}

static constexpr int VIDEO_DECODER_SUCCESS = 0;
static constexpr int VIDEO_DECODER_TRY_AGAIN = 1;
static constexpr int VIDEO_DECODER_END_OF_STREAM = 2;
static constexpr int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static constexpr int VIDEO_DECODER_ERROR_OTHER = -2;
static constexpr int VIDEO_OUTPUT_MODE_NONE = -1;
static constexpr int VIDEO_OUTPUT_MODE_SURFACE_YUV = 1;

// LINT.IfChange(decodeLoadLevel)
static constexpr int DECODE_LOAD_NORMAL = 0;
static constexpr int DECODE_LOAD_NON_REFERENCE = 1;
static constexpr int DECODE_LOAD_AGGRESSIVE = 2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegVideoDecoder.java:decodeLoadLevel)

// LINT.IfChange(dolbyVisionOutputMode)
enum DolbyVisionOutputMode {
  DOLBY_VISION_OUTPUT_MODE_NONE = 0,
  DOLBY_VISION_OUTPUT_MODE_BASE_LAYER = 1,
  DOLBY_VISION_OUTPUT_MODE_PREFER_MAPPING = 2,
  DOLBY_VISION_OUTPUT_MODE_REQUIRE_MAPPING = 3,
};
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java:dolbyVisionOutputMode)

static constexpr jint kMedia3ColorRangeFull = 1;
static constexpr jint kMedia3ColorRangeLimited = 2;

static DolbyVisionMappingPolicy getDolbyVisionMappingPolicy(int outputMode) {
  if (outputMode == DOLBY_VISION_OUTPUT_MODE_REQUIRE_MAPPING) {
    return DolbyVisionMappingPolicy::kRequire;
  }
  if (outputMode == DOLBY_VISION_OUTPUT_MODE_PREFER_MAPPING) {
    return DolbyVisionMappingPolicy::kPrefer;
  }
  return DolbyVisionMappingPolicy::kDisabled;
}

struct PacketMetadata {
  int64_t time_us;
  int flags;
};

struct FrameColorInfo {
  AVColorSpace space = AVCOL_SPC_UNSPECIFIED;
  AVColorRange range = AVCOL_RANGE_UNSPECIFIED;
  AVColorPrimaries primaries = AVCOL_PRI_UNSPECIFIED;
  AVColorTransferCharacteristic transfer = AVCOL_TRC_UNSPECIFIED;
};

static FrameColorInfo getFrameColorInfo(jint matrixCoefficients,
                                        jint colorRange, jint colorPrimaries,
                                        jint colorTransfer) {
  FrameColorInfo colorInfo;
  switch (static_cast<AVColorSpace>(matrixCoefficients)) {
    case AVCOL_SPC_BT709:
      colorInfo.space = AVCOL_SPC_BT709;
      break;
    case AVCOL_SPC_SMPTE170M:
      colorInfo.space = AVCOL_SPC_SMPTE170M;
      break;
    case AVCOL_SPC_BT2020_NCL:
      colorInfo.space = AVCOL_SPC_BT2020_NCL;
      break;
    default:
      break;
  }
  switch (colorRange) {
    case kMedia3ColorRangeFull:
      colorInfo.range = AVCOL_RANGE_JPEG;
      break;
    case kMedia3ColorRangeLimited:
      colorInfo.range = AVCOL_RANGE_MPEG;
      break;
    default:
      break;
  }
  switch (static_cast<AVColorPrimaries>(colorPrimaries)) {
    case AVCOL_PRI_BT709:
      colorInfo.primaries = AVCOL_PRI_BT709;
      break;
    case AVCOL_PRI_BT470BG:
      colorInfo.primaries = AVCOL_PRI_BT470BG;
      break;
    case AVCOL_PRI_BT2020:
      colorInfo.primaries = AVCOL_PRI_BT2020;
      break;
    default:
      break;
  }
  switch (static_cast<AVColorTransferCharacteristic>(colorTransfer)) {
    case AVCOL_TRC_BT709:
      colorInfo.transfer = AVCOL_TRC_BT709;
      break;
    case AVCOL_TRC_GAMMA22:
      colorInfo.transfer = AVCOL_TRC_GAMMA22;
      break;
    case AVCOL_TRC_LINEAR:
      colorInfo.transfer = AVCOL_TRC_LINEAR;
      break;
    case AVCOL_TRC_IEC61966_2_1:
      colorInfo.transfer = AVCOL_TRC_IEC61966_2_1;
      break;
    case AVCOL_TRC_SMPTE2084:
      colorInfo.transfer = AVCOL_TRC_SMPTE2084;
      break;
    case AVCOL_TRC_ARIB_STD_B67:
      colorInfo.transfer = AVCOL_TRC_ARIB_STD_B67;
      break;
    default:
      break;
  }
  return colorInfo;
}

static void applyFrameColorInfoFallback(AVFrame *frame,
                                        const FrameColorInfo &colorInfo) {
  if (frame->colorspace == AVCOL_SPC_UNSPECIFIED) {
    frame->colorspace = colorInfo.space;
  }
  if (frame->color_range == AVCOL_RANGE_UNSPECIFIED) {
    frame->color_range = colorInfo.range;
  }
  if (frame->color_primaries == AVCOL_PRI_UNSPECIFIED) {
    frame->color_primaries = colorInfo.primaries;
  }
  if (frame->color_trc == AVCOL_TRC_UNSPECIFIED) {
    frame->color_trc = colorInfo.transfer;
  }
}

struct JniContext {
  ~JniContext() {
    av_frame_free(&frame);
    av_packet_free(&packet);
    av_buffer_pool_uninit(&packetMetadataPool);
    avcodec_free_context(&codecContext);
  }

  jfieldID decoder_private_field{};
  jmethodID init_for_private_frame_method{};
  jmethodID init_method{};
  jmethodID set_flags_method{};

  AVCodecContext *codecContext{};
  AVFrame *frame{};
  AVPacket *packet{};
  AVBufferPool *packetMetadataPool{};

  int dolbyVisionOutputMode = DOLBY_VISION_OUTPUT_MODE_NONE;
  FrameColorInfo colorInfo;
  int decodeLoadLevel = DECODE_LOAD_NORMAL;
  std::unique_ptr<VideoSurfaceRenderer> surfaceRenderer;
};

static int getDirectVideoBuffer(AVCodecContext *codecContext, AVFrame *frame,
                                int flags) {
  auto *jniContext = static_cast<JniContext *>(codecContext->opaque);
  if (!jniContext || !jniContext->surfaceRenderer) {
    return AVERROR(EINVAL);
  }
  const int result =
      jniContext->surfaceRenderer->GetDirectBuffer(codecContext, frame, flags);
  if (result >= 0 || (result != AVERROR(ENOSYS) && result != AVERROR(ENOMEM))) {
    return result;
  }
  return avcodec_default_get_buffer2(codecContext, frame, flags);
}

static void releasePrivateFrame(JNIEnv *env, JniContext *jniContext,
                                jobject outputBuffer) {
  if (!jniContext || !outputBuffer || !jniContext->decoder_private_field) {
    return;
  }
  auto *frame = reinterpret_cast<AVFrame *>(
      env->GetLongField(outputBuffer, jniContext->decoder_private_field));
  if (frame) {
    av_frame_free(&frame);
    env->SetLongField(outputBuffer, jniContext->decoder_private_field, 0);
  }
}

static bool setDolbyVisionConfiguration(AVCodecContext *codecContext,
                                        JNIEnv *env,
                                        jbyteArray configurationData) {
  if (!configurationData) {
    return true;
  }
  jsize configurationSize = env->GetArrayLength(configurationData);
  if (configurationSize < 4 || configurationSize > (1 << 30)) {
    LOGE("Invalid Dolby Vision configuration size: %d.", configurationSize);
    return false;
  }

  jbyte configuration[5] = {};
  jsize bytesToRead = configurationSize < 5 ? configurationSize : 5;
  env->GetByteArrayRegion(configurationData, 0, bytesToRead, configuration);
  if (env->ExceptionCheck()) {
    LOGE("Failed to read Dolby Vision configuration.");
    return false;
  }

  size_t doviSize;
  AVDOVIDecoderConfigurationRecord *dovi = av_dovi_alloc(&doviSize);
  if (!dovi) {
    LOGE("Failed to allocate Dolby Vision configuration.");
    return false;
  }
  dovi->dv_version_major = static_cast<uint8_t>(configuration[0]);
  dovi->dv_version_minor = static_cast<uint8_t>(configuration[1]);
  uint16_t profileLevelAndFlags =
      static_cast<uint16_t>(static_cast<uint8_t>(configuration[2])) << 8 |
      static_cast<uint8_t>(configuration[3]);
  dovi->dv_profile = (profileLevelAndFlags >> 9) & 0x7F;
  dovi->dv_level = (profileLevelAndFlags >> 3) & 0x3F;
  dovi->rpu_present_flag = (profileLevelAndFlags >> 2) & 0x01;
  dovi->el_present_flag = (profileLevelAndFlags >> 1) & 0x01;
  dovi->bl_present_flag = profileLevelAndFlags & 0x01;
  if (configurationSize >= 5) {
    uint8_t compatibilityAndCompression =
        static_cast<uint8_t>(configuration[4]);
    dovi->dv_bl_signal_compatibility_id =
        (compatibilityAndCompression >> 4) & 0x0F;
    dovi->dv_md_compression = (compatibilityAndCompression >> 2) & 0x03;
  } else {
    dovi->dv_bl_signal_compatibility_id = 0;
    dovi->dv_md_compression = AV_DOVI_COMPRESSION_NONE;
  }

  if (!av_packet_side_data_add(
          &codecContext->coded_side_data, &codecContext->nb_coded_side_data,
          AV_PKT_DATA_DOVI_CONF, dovi, doviSize, /* flags= */ 0)) {
    av_free(dovi);
    LOGE("Failed to attach Dolby Vision configuration.");
    return false;
  }
  return true;
}

static AVCodecContext *createConfiguredCodecContext(
    JNIEnv *env, const AVCodec *codec, jbyteArray extraData,
    jbyteArray dolbyVisionConfig, jint threads, jint width, jint height,
    const FrameColorInfo &colorInfo) {
  AVCodecContext *codecContext = avcodec_alloc_context3(codec);
  if (!codecContext) {
    LOGE("Failed to allocate context.");
    return nullptr;
  }

  if (!setCodecExtraData(env, extraData, codecContext)) {
    avcodec_free_context(&codecContext);
    return nullptr;
  }
  if (!setDolbyVisionConfiguration(codecContext, env, dolbyVisionConfig)) {
    avcodec_free_context(&codecContext);
    return nullptr;
  }

  codecContext->thread_count = threads;

  if (codec->capabilities & AV_CODEC_CAP_FRAME_THREADS) {
    codecContext->thread_type = FF_THREAD_FRAME;
  } else if (codec->capabilities & AV_CODEC_CAP_SLICE_THREADS) {
    codecContext->thread_type = FF_THREAD_SLICE;
  }
  codecContext->flags |= AV_CODEC_FLAG_COPY_OPAQUE;
  codecContext->pkt_timebase = AV_TIME_BASE_Q;
  codecContext->colorspace = colorInfo.space;
  codecContext->color_range = colorInfo.range;
  codecContext->color_primaries = colorInfo.primaries;
  codecContext->color_trc = colorInfo.transfer;
  if (width > 0 && height > 0) {
    codecContext->width = width;
    codecContext->height = height;
    codecContext->coded_width = width;
    codecContext->coded_height = height;
  }
  return codecContext;
}

static JniContext *createVideoContext(JNIEnv *env, const AVCodec *codec,
                                      jbyteArray extraData,
                                      jbyteArray dolbyVisionConfig,
                                      jint threads, jint width, jint height,
                                      jint dolbyVisionOutputMode,
                                      const FrameColorInfo &colorInfo) {
  auto jniContext = std::make_unique<JniContext>();

  jniContext->codecContext =
      createConfiguredCodecContext(env, codec, extraData, dolbyVisionConfig,
                                   threads, width, height, colorInfo);
  if (!jniContext->codecContext) {
    return nullptr;
  }
  AVCodecContext *const codecContext = jniContext->codecContext;
  jniContext->surfaceRenderer = std::make_unique<VideoSurfaceRenderer>();
  if (!jniContext->surfaceRenderer->Initialize()) {
    LOGE("Failed to initialize GLES output for FFmpeg decoder %s.",
         codec->name);
    return nullptr;
  }

  const bool useDirectRendering =
      (codec->capabilities & AV_CODEC_CAP_DR1) != 0 &&
      jniContext->surfaceRenderer->IsDirectRenderingEnabled();
  if (useDirectRendering) {
    codecContext->opaque = jniContext.get();
    codecContext->get_buffer2 = getDirectVideoBuffer;
  }
  AVDictionary *decoderOptions = nullptr;
  if (useDirectRendering && strcmp(codec->name, "libdav1d") == 0) {
    int optionResult =
        av_dict_set(&decoderOptions, "direct_rendering", "1", /* flags= */ 0);
    if (optionResult < 0) {
      logError("av_dict_set(libdav1d direct_rendering)", optionResult);
      av_dict_free(&decoderOptions);
      return nullptr;
    }
  }
  int result = avcodec_open2(codecContext, codec, &decoderOptions);
  if (result >= 0 && av_dict_count(decoderOptions) != 0) {
    LOGE("FFmpeg decoder %s did not accept its direct-rendering option.",
         codec->name);
    result = AVERROR(EINVAL);
  }
  av_dict_free(&decoderOptions);
  if (result < 0) {
    logError("avcodec_open2", result);
    return nullptr;
  }

  jniContext->dolbyVisionOutputMode = dolbyVisionOutputMode;
  jniContext->colorInfo = colorInfo;
  jniContext->frame = av_frame_alloc();
  if (!jniContext->frame) {
    LOGE("Failed to allocate AVFrame.");
    return nullptr;
  }

  jniContext->packet = av_packet_alloc();
  if (!jniContext->packet) {
    LOGE("Failed to allocate reusable AVPacket.");
    return nullptr;
  }
  jniContext->packetMetadataPool =
      av_buffer_pool_init(sizeof(PacketMetadata), /* alloc= */ nullptr);
  if (!jniContext->packetMetadataPool) {
    LOGE("Failed to allocate packet metadata pool.");
    return nullptr;
  }

  jclass outputBufferClass =
      env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (env->ExceptionCheck() || !outputBufferClass) {
    LOGE("Failed to find VideoDecoderOutputBuffer class.");
    return nullptr;
  }
  auto failLookup = [&]() {
    LOGE("Failed to cache VideoDecoderOutputBuffer JNI fields.");
    env->DeleteLocalRef(outputBufferClass);
    return static_cast<JniContext *>(nullptr);
  };
  jniContext->decoder_private_field =
      env->GetFieldID(outputBufferClass, "decoderPrivate", "J");
  if (env->ExceptionCheck() || !jniContext->decoder_private_field) {
    return failLookup();
  }
  jniContext->init_for_private_frame_method =
      env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
  if (env->ExceptionCheck() || !jniContext->init_for_private_frame_method) {
    return failLookup();
  }
  jniContext->init_method =
      env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");
  if (env->ExceptionCheck() || !jniContext->init_method) {
    return failLookup();
  }
  jniContext->set_flags_method =
      env->GetMethodID(outputBufferClass, "setFlags", "(I)V");
  if (env->ExceptionCheck() || !jniContext->set_flags_method) {
    return failLookup();
  }
  env->DeleteLocalRef(outputBufferClass);

  return jniContext.release();
}

extern "C" JNIEXPORT jlong JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegInitialize(
    JNIEnv *env, jobject, jstring codec_name, jbyteArray extra_data,
    jbyteArray dolby_vision_config, jint threads, jint width, jint height,
    jint dolby_vision_output_mode, jint matrix_coefficients, jint color_range,
    jint color_primaries, jint color_transfer) {
  const AVCodec *codec = getCodecByName(env, codec_name);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }

  return reinterpret_cast<jlong>(
      createVideoContext(env, codec, extra_data, dolby_vision_config, threads,
                         width, height, dolby_vision_output_mode,
                         getFrameColorInfo(matrix_coefficients, color_range,
                                           color_primaries, color_transfer)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegReset(
    JNIEnv *, jobject, jlong jContext) {
  if (!jContext) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  AVCodecContext *context = jniContext->codecContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  avcodec_flush_buffers(context);
  context->skip_frame = AVDISCARD_DEFAULT;
  context->skip_loop_filter = AVDISCARD_DEFAULT;
  jniContext->decodeLoadLevel = DECODE_LOAD_NORMAL;
  av_frame_unref(jniContext->frame);
  av_packet_unref(jniContext->packet);
  return (jlong)jniContext;
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegRelease(
    JNIEnv *env, jobject, jlong jContext) {
  if (!jContext) {
    return;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  if (jniContext->surfaceRenderer) {
    jniContext->surfaceRenderer->Detach(env);
  }
  delete jniContext;
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegDetachSurface(
    JNIEnv *env, jobject, jlong jContext) {
  if (!jContext) {
    return;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  if (jniContext->surfaceRenderer) {
    jniContext->surfaceRenderer->Detach(env);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegFlushSurface(
    JNIEnv *, jobject, jlong jContext) {
  if (!jContext) {
    return;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  if (jniContext->surfaceRenderer) {
    jniContext->surfaceRenderer->Flush();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegReleaseFrame(
    JNIEnv *, jclass, jlong jFrame) {
  auto *frame = reinterpret_cast<AVFrame *>(jFrame);
  av_frame_free(&frame);
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegRenderFrame(
    JNIEnv *env, jobject, jlong jContext, jobject surface, jlong jFrame,
    jint displayed_width, jint displayed_height, jlong release_time_ns,
    jint rotation_degrees) {
  if (!jContext) {
    LOGE("Context must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (!jFrame || displayed_width <= 0 || displayed_height <= 0) {
    LOGE("Invalid render target or dimensions.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  auto *privateFrame = reinterpret_cast<AVFrame *>(jFrame);
  if (!jniContext->surfaceRenderer) {
    LOGE("GLES Surface renderer is unavailable.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  switch (jniContext->surfaceRenderer->Render(
      env, surface, privateFrame, displayed_width, displayed_height,
      release_time_ns, rotation_degrees,
      getDolbyVisionMappingPolicy(jniContext->dolbyVisionOutputMode))) {
    case VideoRenderResult::kSuccess:
      return VIDEO_DECODER_SUCCESS;
    case VideoRenderResult::kTryAgain:
      return VIDEO_DECODER_TRY_AGAIN;
    case VideoRenderResult::kError:
      return VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_ERROR_OTHER;
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegSendPacket(
    JNIEnv *env, jobject, jlong jContext, jobject encoded_data, jint offset,
    jint length, jlong input_time, jint flags, jint decode_load_level) {
  if (!jContext) {
    LOGE("Context must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (!encoded_data || offset < 0 || length < 0) {
    LOGE("Invalid encoded input buffer.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  AVCodecContext *avContext = jniContext->codecContext;

  auto *inputBuffer = (uint8_t *)env->GetDirectBufferAddress(encoded_data);
  jlong inputCapacity = env->GetDirectBufferCapacity(encoded_data);
  if ((length > 0 && !inputBuffer) || inputCapacity < 0 ||
      (int64_t)offset + length > inputCapacity) {
    LOGE("Encoded input buffer must be direct.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  AVPacket *packet = jniContext->packet;
  if (!packet) {
    LOGE("Video packet cache is not initialized.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  av_packet_unref(packet);
  packet->data = inputBuffer ? inputBuffer + offset : nullptr;
  packet->size = length;
  packet->pts = input_time;
  AVBufferRef *metadataBuffer =
      av_buffer_pool_get(jniContext->packetMetadataPool);
  if (!metadataBuffer) {
    av_packet_unref(packet);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  auto *metadata = reinterpret_cast<PacketMetadata *>(metadataBuffer->data);
  metadata->time_us = input_time;
  metadata->flags = flags;
  packet->opaque_ref = metadataBuffer;

  if (decode_load_level != DECODE_LOAD_NORMAL &&
      decode_load_level != DECODE_LOAD_NON_REFERENCE &&
      decode_load_level != DECODE_LOAD_AGGRESSIVE) {
    LOGE("Invalid FFmpeg decoder load level: %d.", decode_load_level);
    av_packet_unref(packet);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (jniContext->decodeLoadLevel != decode_load_level) {
    // Preserve reference pictures at every level. Aggressive recovery only
    // trades in-loop filtering quality on bidirectional frames for throughput.
    avContext->skip_frame = decode_load_level == DECODE_LOAD_NORMAL
                                ? AVDISCARD_DEFAULT
                                : AVDISCARD_NONREF;
    avContext->skip_loop_filter = decode_load_level == DECODE_LOAD_AGGRESSIVE
                                      ? AVDISCARD_BIDIR
                                      : AVDISCARD_DEFAULT;
    jniContext->decodeLoadLevel = decode_load_level;
  }
  int result = avcodec_send_packet(avContext, packet);
  av_packet_unref(packet);
  if (result == AVERROR(EAGAIN)) {
    return VIDEO_DECODER_TRY_AGAIN;
  }
  if (result < 0) {
    logError("avcodec_send_packet", result);
    return result == AVERROR_INVALIDDATA ? VIDEO_DECODER_ERROR_INVALID_DATA
                                         : VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegSendEndOfStream(
    JNIEnv *, jobject, jlong jContext) {
  if (!jContext) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  int result = avcodec_send_packet(jniContext->codecContext, nullptr);
  if (result == AVERROR(EAGAIN)) {
    return VIDEO_DECODER_TRY_AGAIN;
  }
  if (result == AVERROR_EOF) {
    return VIDEO_DECODER_SUCCESS;
  }
  if (result < 0) {
    logError("avcodec_send_packet(NULL)", result);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_ffmpegReceiveFrame(
    JNIEnv *env, jobject, jlong jContext, jint output_mode,
    jobject output_buffer) {
  if (!jContext) {
    LOGE("Context must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (!output_buffer) {
    LOGE("Output buffer must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
  AVCodecContext *avContext = jniContext->codecContext;

  AVFrame *frame = jniContext->frame;
  av_frame_unref(frame);
  int result = avcodec_receive_frame(avContext, frame);

  if (result == AVERROR(EAGAIN)) {
    return VIDEO_DECODER_TRY_AGAIN;
  }
  if (result == AVERROR_EOF) {
    return VIDEO_DECODER_END_OF_STREAM;
  }
  if (result < 0) {
    logError("avcodec_receive_frame", result);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if ((frame->flags & AV_FRAME_FLAG_CORRUPT) != 0) {
    LOGE("Dropping corrupt decoded frame (decode_error_flags=%d).",
         frame->decode_error_flags);
    return VIDEO_DECODER_ERROR_INVALID_DATA;
  }
  applyFrameColorInfoFallback(frame, jniContext->colorInfo);

  auto *metadata =
      frame->opaque_ref && frame->opaque_ref->size >= sizeof(PacketMetadata)
          ? reinterpret_cast<PacketMetadata *>(frame->opaque_ref->data)
          : nullptr;
  int64_t pts = frame->pts;
  if (pts == AV_NOPTS_VALUE) {
    pts = frame->best_effort_timestamp;
  }
  if (pts == AV_NOPTS_VALUE && metadata) {
    pts = metadata->time_us;
  }
  if (pts == AV_NOPTS_VALUE) {
    LOGE("Decoded frame has no presentation timestamp.");
    return VIDEO_DECODER_ERROR_INVALID_DATA;
  }
  env->CallVoidMethod(output_buffer, jniContext->init_method, pts, output_mode,
                      nullptr);
  if (env->ExceptionCheck()) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  env->CallVoidMethod(output_buffer, jniContext->set_flags_method,
                      metadata ? metadata->flags : 0);
  if (env->ExceptionCheck()) {
    return VIDEO_DECODER_ERROR_OTHER;
  }

  if (av_frame_apply_cropping(frame, 0) < 0) {
    LOGE("Failed to apply decoded frame cropping.");
    return VIDEO_DECODER_ERROR_OTHER;
  }

  releasePrivateFrame(env, jniContext, output_buffer);
  if (output_mode == VIDEO_OUTPUT_MODE_NONE) {
    env->CallVoidMethod(output_buffer,
                        jniContext->init_for_private_frame_method, frame->width,
                        frame->height);
    return env->ExceptionCheck() ? VIDEO_DECODER_ERROR_OTHER
                                 : VIDEO_DECODER_SUCCESS;
  }
  if (output_mode != VIDEO_OUTPUT_MODE_SURFACE_YUV) {
    LOGE("Unsupported FFmpeg video output mode: %d.", output_mode);
    return VIDEO_DECODER_ERROR_OTHER;
  }

  AVFrame *surfaceFrame = frame;
  if (!jniContext->surfaceRenderer->SupportsFrame(surfaceFrame)) {
    const char *pixelFormat =
        av_get_pix_fmt_name(static_cast<AVPixelFormat>(surfaceFrame->format));
    LOGE("GLES Surface output does not support decoded format %s.",
         pixelFormat ? pixelFormat : "unknown");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  AVFrame *privateFrame = av_frame_clone(surfaceFrame);
  if (!privateFrame) {
    LOGE("Failed to retain decoded frame.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  env->CallVoidMethod(output_buffer, jniContext->init_for_private_frame_method,
                      privateFrame->width, privateFrame->height);
  if (env->ExceptionCheck()) {
    av_frame_free(&privateFrame);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  env->SetLongField(output_buffer, jniContext->decoder_private_field,
                    (jlong)privateFrame);
  return VIDEO_DECODER_SUCCESS;
}
