#include "ffcommon.h"

extern "C" {
#include <libavutil/mem.h>
}

const AVCodec *getCodecByName(JNIEnv *env, jstring codecName) {
  if (!codecName) {
    return nullptr;
  }
  const char *codecNameChars = env->GetStringUTFChars(codecName, nullptr);
  if (!codecNameChars) {
    return nullptr;
  }
  const AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

bool setCodecExtraData(JNIEnv *env, jbyteArray extraData,
                       AVCodecContext *codecContext) {
  if (!extraData) {
    return true;
  }
  if (!env || !codecContext) {
    return false;
  }
  const jsize size = env->GetArrayLength(extraData);
  if (env->ExceptionCheck()) {
    LOGE("Failed to get codec extradata size.");
    return false;
  }
  if (size == 0) {
    return true;
  }

  const size_t allocationSize =
      static_cast<size_t>(size) + AV_INPUT_BUFFER_PADDING_SIZE;
  auto *data = static_cast<uint8_t *>(av_mallocz(allocationSize));
  if (!data) {
    LOGE("Failed to allocate codec extradata.");
    return false;
  }
  env->GetByteArrayRegion(extraData, 0, size, reinterpret_cast<jbyte *>(data));
  if (env->ExceptionCheck()) {
    LOGE("Failed to copy codec extradata.");
    av_free(data);
    return false;
  }
  codecContext->extradata = data;
  codecContext->extradata_size = size;
  return true;
}

void logError(const char *functionName, int errorNumber) {
  char buffer[kErrorStringBufferLength];
  if (av_strerror(errorNumber, buffer, sizeof(buffer)) < 0) {
    LOGE("Error in %s: %d", functionName, errorNumber);
    return;
  }
  LOGE("Error in %s: %s", functionName, buffer);
}
