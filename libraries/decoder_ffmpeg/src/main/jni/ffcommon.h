#ifndef MEDIA3_FFCOMMON_H
#define MEDIA3_FFCOMMON_H

#include <android/log.h>
#include <jni.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

inline constexpr int kErrorStringBufferLength = 256;

const AVCodec *getCodecByName(JNIEnv *env, jstring codecName);

bool setCodecExtraData(JNIEnv *env, jbyteArray extraData,
                       AVCodecContext *codecContext);

void logError(const char *functionName, int errorNumber);

#endif  // MEDIA3_FFCOMMON_H
