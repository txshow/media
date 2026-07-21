#include "ffvideo_frame.h"

#include <limits>
#include <new>
#include <utility>

extern "C" {
#include <libavutil/buffer.h>
#include <libavutil/error.h>
#include <libavutil/pixdesc.h>
}

namespace {

constexpr size_t kDirectPlanePadding = 64;

struct DirectFrameLease {
  std::weak_ptr<DirectFramePool> pool;
  std::shared_ptr<DirectFrameSlot> slot;
};

void ReleaseDirectFrame(void *opaque, uint8_t *) {
  std::unique_ptr<DirectFrameLease> lease(
      static_cast<DirectFrameLease *>(opaque));
  if (lease) {
    if (auto pool = lease->pool.lock()) {
      pool->Release(lease->slot);
    }
  }
}

}  // namespace

bool GetYuvPixelFormat(AVPixelFormat pixel_format, YuvFormat *format) {
  if (!format) {
    return false;
  }
  const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(pixel_format);
  if (!descriptor || descriptor->nb_components != kVideoPlaneCount ||
      (descriptor->flags & (AV_PIX_FMT_FLAG_BE | AV_PIX_FMT_FLAG_PAL |
                            AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_BAYER |
                            AV_PIX_FMT_FLAG_HWACCEL | AV_PIX_FMT_FLAG_FLOAT)) ||
      !(descriptor->flags & AV_PIX_FMT_FLAG_PLANAR)) {
    return false;
  }

  const int depth = descriptor->comp[0].depth;
  const int bytes_per_sample = depth <= 8 ? 1 : 2;
  if (depth < 8 || depth > 16) {
    return false;
  }
  for (int component = 0; component < kVideoPlaneCount; ++component) {
    const AVComponentDescriptor &component_descriptor =
        descriptor->comp[component];
    if (component_descriptor.plane != component ||
        component_descriptor.offset != 0 || component_descriptor.shift != 0 ||
        component_descriptor.depth != depth ||
        component_descriptor.step != bytes_per_sample) {
      return false;
    }
  }

  format->depth = depth;
  format->bytes_per_sample = bytes_per_sample;
  format->chroma_width_shift = descriptor->log2_chroma_w;
  format->chroma_height_shift = descriptor->log2_chroma_h;
  return true;
}

bool GetYuvFormat(const AVFrame *frame, YuvFormat *format) {
  if (!frame || frame->width <= 0 || frame->height <= 0 ||
      !GetYuvPixelFormat(static_cast<AVPixelFormat>(frame->format), format)) {
    return false;
  }
  for (int component = 0; component < kVideoPlaneCount; ++component) {
    if (!frame->data[component] || frame->linesize[component] <= 0 ||
        frame->linesize[component] % format->bytes_per_sample != 0) {
      return false;
    }
  }

  const int chroma_width =
      AV_CEIL_RSHIFT(frame->width, format->chroma_width_shift);
  const int chroma_height =
      AV_CEIL_RSHIFT(frame->height, format->chroma_height_shift);
  const int64_t minimum_luma_linesize =
      static_cast<int64_t>(frame->width) * format->bytes_per_sample;
  const int64_t minimum_chroma_linesize =
      static_cast<int64_t>(chroma_width) * format->bytes_per_sample;
  return frame->linesize[0] >= minimum_luma_linesize &&
         frame->linesize[1] >= minimum_chroma_linesize &&
         frame->linesize[2] >= minimum_chroma_linesize && chroma_height > 0;
}

bool operator==(const DirectFrameLayout &left, const DirectFrameLayout &right) {
  if (left.format != right.format ||
      left.allocated_width != right.allocated_width ||
      left.allocated_height != right.allocated_height ||
      left.total_size != right.total_size) {
    return false;
  }
  for (int plane = 0; plane < kVideoPlaneCount; ++plane) {
    if (left.linesize[plane] != right.linesize[plane] ||
        left.plane_offset[plane] != right.plane_offset[plane] ||
        left.plane_size[plane] != right.plane_size[plane]) {
      return false;
    }
  }
  return true;
}

bool AlignSize(size_t value, size_t alignment, size_t *aligned_value) {
  if (!aligned_value || alignment == 0 || (alignment & (alignment - 1)) != 0 ||
      value > std::numeric_limits<size_t>::max() - (alignment - 1)) {
    return false;
  }
  *aligned_value = (value + alignment - 1) & ~(alignment - 1);
  return true;
}

bool BuildDirectFrameLayout(AVCodecContext *codec_context, AVFrame *frame,
                            DirectFrameLayout *layout) {
  if (!codec_context || !frame || !layout || frame->width <= 0 ||
      frame->height <= 0) {
    return false;
  }
  YuvFormat format = {};
  const auto pixel_format = static_cast<AVPixelFormat>(frame->format);
  if (!GetYuvPixelFormat(pixel_format, &format)) {
    return false;
  }

  int allocated_width = frame->width;
  int allocated_height = frame->height;
  int stride_alignment[AV_NUM_DATA_POINTERS] = {};
  avcodec_align_dimensions2(codec_context, &allocated_width, &allocated_height,
                            stride_alignment);
  if (allocated_width <= 0 || allocated_height <= 0) {
    return false;
  }

  int linesize[kVideoPlaneCount] = {};
  bool aligned = false;
  for (int attempt = 0; attempt < 32; ++attempt) {
    const int chroma_width =
        AV_CEIL_RSHIFT(allocated_width, format.chroma_width_shift);
    const int64_t luma_linesize =
        static_cast<int64_t>(allocated_width) * format.bytes_per_sample;
    const int64_t chroma_linesize =
        static_cast<int64_t>(chroma_width) * format.bytes_per_sample;
    if (luma_linesize > std::numeric_limits<int>::max() ||
        chroma_linesize > std::numeric_limits<int>::max()) {
      return false;
    }
    linesize[0] = static_cast<int>(luma_linesize);
    linesize[1] = static_cast<int>(chroma_linesize);
    linesize[2] = static_cast<int>(chroma_linesize);
    aligned = true;
    for (int plane = 0; plane < kVideoPlaneCount; ++plane) {
      if (stride_alignment[plane] > 0 &&
          linesize[plane] % stride_alignment[plane] != 0) {
        aligned = false;
        break;
      }
    }
    if (aligned) {
      break;
    }
    const int increment = allocated_width & -allocated_width;
    if (increment <= 0 ||
        allocated_width > std::numeric_limits<int>::max() - increment) {
      return false;
    }
    allocated_width += increment;
  }
  if (!aligned) {
    return false;
  }

  DirectFrameLayout result = {};
  result.format = pixel_format;
  result.allocated_width = allocated_width;
  result.allocated_height = allocated_height;
  size_t next_offset = 0;
  for (int plane = 0; plane < kVideoPlaneCount; ++plane) {
    const int plane_height =
        plane == 0
            ? allocated_height
            : AV_CEIL_RSHIFT(allocated_height, format.chroma_height_shift);
    if (plane_height <= 0 ||
        static_cast<size_t>(linesize[plane]) >
            std::numeric_limits<size_t>::max() /
                static_cast<size_t>(plane_height) ||
        !AlignSize(next_offset, kDirectBufferAlignment,
                   &result.plane_offset[plane])) {
      return false;
    }
    result.linesize[plane] = linesize[plane];
    result.plane_size[plane] = static_cast<size_t>(linesize[plane]) *
                               static_cast<size_t>(plane_height);
    if (result.plane_size[plane] >
        std::numeric_limits<size_t>::max() - kDirectPlanePadding) {
      return false;
    }
    const size_t padded_plane_size =
        result.plane_size[plane] + kDirectPlanePadding;
    if (result.plane_offset[plane] >
        std::numeric_limits<size_t>::max() - padded_plane_size) {
      return false;
    }
    next_offset = result.plane_offset[plane] + padded_plane_size;
  }
  result.total_size = next_offset;
  if (result.total_size == 0 ||
      result.total_size >
          static_cast<size_t>(std::numeric_limits<std::ptrdiff_t>::max()) -
              (kDirectBufferAlignment - 1)) {
    return false;
  }
  *layout = result;
  return true;
}

std::shared_ptr<DirectFrameSlot> DirectFramePool::TryAcquire(
    const DirectFrameLayout &layout) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (stopped_) {
    return nullptr;
  }
  for (const auto &slot : slots_) {
    if (!slot->leased && slot->layout == layout) {
      slot->leased = true;
      return slot;
    }
  }
  return nullptr;
}

bool DirectFramePool::AddLeasedSlot(
    const std::shared_ptr<DirectFrameSlot> &slot) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (stopped_ || !slot) {
    return false;
  }
  slot->leased = true;
  slots_.push_back(slot);
  return true;
}

void DirectFramePool::Release(const std::shared_ptr<DirectFrameSlot> &slot) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (slot) {
    slot->leased = false;
  }
}

std::shared_ptr<DirectFrameSlot> DirectFramePool::Find(
    const uint8_t *data) const {
  if (!data) {
    return nullptr;
  }
  const uintptr_t address = reinterpret_cast<uintptr_t>(data);
  std::lock_guard<std::mutex> lock(mutex_);
  for (const auto &slot : slots_) {
    if (!slot || !slot->mapped_base || slot->buffer_size == 0) {
      continue;
    }
    const uintptr_t start = reinterpret_cast<uintptr_t>(slot->mapped_base);
    if (address >= start && address - start < slot->buffer_size) {
      return slot;
    }
  }
  return nullptr;
}

std::vector<std::shared_ptr<DirectFrameSlot>> DirectFramePool::TakeIdleSlots(
    const DirectFrameLayout &layout, size_t retained_compatible_slots) {
  std::vector<std::shared_ptr<DirectFrameSlot>> idle_slots;
  std::lock_guard<std::mutex> lock(mutex_);
  size_t compatible_idle_slots = 0;
  for (auto iterator = slots_.begin(); iterator != slots_.end();) {
    if ((*iterator)->leased) {
      ++iterator;
      continue;
    }
    if ((*iterator)->layout == layout &&
        compatible_idle_slots < retained_compatible_slots) {
      ++compatible_idle_slots;
      ++iterator;
    } else {
      idle_slots.push_back(std::move(*iterator));
      iterator = slots_.erase(iterator);
    }
  }
  return idle_slots;
}

std::vector<std::shared_ptr<DirectFrameSlot>>
DirectFramePool::StopAndTakeSlots() {
  std::lock_guard<std::mutex> lock(mutex_);
  stopped_ = true;
  // Returned Java output buffers may retain leases after decoder release.
  // Their AVFrames are inert at this point and must not block GPU teardown.
  return std::move(slots_);
}

int BindDirectFrame(const std::shared_ptr<DirectFramePool> &pool,
                    const std::shared_ptr<DirectFrameSlot> &slot,
                    AVFrame *frame) {
  if (!pool || !slot || !frame || !slot->data) {
    return AVERROR(EINVAL);
  }
  auto *lease = new (std::nothrow) DirectFrameLease{pool, slot};
  if (!lease) {
    pool->Release(slot);
    return AVERROR(ENOMEM);
  }
  AVBufferRef *buffer =
      av_buffer_create(slot->data, slot->layout.total_size, ReleaseDirectFrame,
                       lease, /* flags= */ 0);
  if (!buffer) {
    ReleaseDirectFrame(lease, slot->data);
    return AVERROR(ENOMEM);
  }

  frame->buf[0] = buffer;
  for (int plane = 0; plane < kVideoPlaneCount; ++plane) {
    frame->data[plane] = slot->data + slot->layout.plane_offset[plane];
    frame->linesize[plane] = slot->layout.linesize[plane];
  }
  frame->extended_data = frame->data;
  return 0;
}
