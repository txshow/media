#ifndef MEDIA3_FFVIDEO_PLACEBO_H
#define MEDIA3_FFVIDEO_PLACEBO_H

#include <memory>

struct AVFrame;

struct PlaceboTexturePlane {
  unsigned int texture = 0;
  int width = 0;
  int height = 0;
  int internal_format = 0;
};

enum class PlaceboOutputColorMode {
  kSdr,
  kBt2020Pq,
  kBt2020Hlg,
};

// Returns whether FFmpeg exported Dolby Vision metadata that can be mapped
// safely for this frame, including its coefficient and bit-depth validation.
bool CanMapDolbyVisionMetadata(const AVFrame *frame);

// Thin libplacebo OpenGL adapter. The caller continues to own EGL, frame
// scheduling, texture uploads, presentation timestamps and buffer swaps.
class PlaceboVideoRenderer final {
 public:
  PlaceboVideoRenderer();
  ~PlaceboVideoRenderer();

  PlaceboVideoRenderer(const PlaceboVideoRenderer &) = delete;
  PlaceboVideoRenderer &operator=(const PlaceboVideoRenderer &) = delete;

  // The supplied EGL context must be current for every method below.
  bool Initialize(void *egl_display, void *egl_context);
  bool Render(const AVFrame *frame, const PlaceboTexturePlane planes[3],
              int texture_set_index, int target_width, int target_height,
              int rotation_degrees, PlaceboOutputColorMode output_color_mode,
              bool apply_dolby_vision_mapping);
  void Flush();
  void Shutdown();
  void Abandon();

 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};

#endif  // MEDIA3_FFVIDEO_PLACEBO_H
