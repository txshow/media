#ifndef MEDIA3_FFVIDEO_SURFACE_H
#define MEDIA3_FFVIDEO_SURFACE_H

#include <jni.h>

#include <cstdint>
#include <memory>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
}

enum class DolbyVisionMappingPolicy {
  kDisabled,
  kPrefer,
  kRequire,
};

enum class VideoRenderResult {
  kSuccess,
  kTryAgain,
  kError,
};

// Uploads software-decoded AVFrames to GLES textures and presents them to the
// ExoPlayer Surface on a dedicated thread. Persistent direct-render buffers
// are used when available; ordinary FFmpeg frames use client-memory uploads.
class VideoSurfaceRenderer final {
 public:
  VideoSurfaceRenderer();
  ~VideoSurfaceRenderer();

  VideoSurfaceRenderer(const VideoSurfaceRenderer &) = delete;
  VideoSurfaceRenderer &operator=(const VideoSurfaceRenderer &) = delete;

  // Initializes the offscreen GLES resource context. This must complete before
  // avcodec_open2 optionally installs GetDirectBuffer as
  // AVCodecContext.get_buffer2.
  bool Initialize();
  bool IsDirectRenderingEnabled() const;
  int GetDirectBuffer(AVCodecContext *codec_context, AVFrame *frame, int flags);
  bool IsDirectFrame(const AVFrame *frame) const;
  bool SupportsFrame(const AVFrame *frame) const;
  // Takes ownership of frame only when kSuccess is returned.
  VideoRenderResult Render(
      JNIEnv *env, jobject surface, AVFrame *frame, int displayed_width,
      int displayed_height, int64_t release_time_ns, int rotation_degrees,
      DolbyVisionMappingPolicy dolby_vision_mapping_policy);
  void Flush();
  void Detach(JNIEnv *env);

 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};

#endif  // MEDIA3_FFVIDEO_SURFACE_H
