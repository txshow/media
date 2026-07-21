#ifndef MEDIA3_FFVIDEO_FRAME_H
#define MEDIA3_FFVIDEO_FRAME_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
}

constexpr int kVideoPlaneCount = 3;
constexpr size_t kDirectBufferAlignment = 64;

struct YuvFormat {
  int depth = 0;
  int bytes_per_sample = 0;
  int chroma_width_shift = 0;
  int chroma_height_shift = 0;
};

bool GetYuvPixelFormat(AVPixelFormat pixel_format, YuvFormat *format);
bool GetYuvFormat(const AVFrame *frame, YuvFormat *format);

struct DirectFrameLayout {
  AVPixelFormat format = AV_PIX_FMT_NONE;
  int allocated_width = 0;
  int allocated_height = 0;
  int linesize[kVideoPlaneCount] = {};
  size_t plane_offset[kVideoPlaneCount] = {};
  size_t plane_size[kVideoPlaneCount] = {};
  size_t total_size = 0;
};

bool operator==(const DirectFrameLayout &left, const DirectFrameLayout &right);
bool AlignSize(size_t value, size_t alignment, size_t *aligned_value);
bool BuildDirectFrameLayout(AVCodecContext *codec_context, AVFrame *frame,
                            DirectFrameLayout *layout);

// Backend-owned GPU storage that is also persistently mapped for FFmpeg DR1.
// The pool owns only the lease state; creation and destruction stay on the
// renderer thread that owns the graphics context.
struct DirectFrameSlot {
  DirectFrameLayout layout;
  uint32_t buffer = 0;
  void *mapped_base = nullptr;
  uint8_t *data = nullptr;
  size_t buffer_size = 0;
  bool leased = false;
};

class DirectFramePool final {
 public:
  std::shared_ptr<DirectFrameSlot> TryAcquire(const DirectFrameLayout &layout);
  bool AddLeasedSlot(const std::shared_ptr<DirectFrameSlot> &slot);
  void Release(const std::shared_ptr<DirectFrameSlot> &slot);
  std::shared_ptr<DirectFrameSlot> Find(const uint8_t *data) const;
  std::vector<std::shared_ptr<DirectFrameSlot>> TakeIdleSlots(
      const DirectFrameLayout &layout, size_t retained_compatible_slots);
  std::vector<std::shared_ptr<DirectFrameSlot>> StopAndTakeSlots();

 private:
  mutable std::mutex mutex_;
  bool stopped_ = false;
  std::vector<std::shared_ptr<DirectFrameSlot>> slots_;
};

int BindDirectFrame(const std::shared_ptr<DirectFramePool> &pool,
                    const std::shared_ptr<DirectFrameSlot> &slot,
                    AVFrame *frame);

#endif  // MEDIA3_FFVIDEO_FRAME_H
