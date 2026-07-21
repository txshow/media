#include "ffvideo_placebo.h"

#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <android/log.h>
#include <libplacebo/log.h>
#include <libplacebo/opengl.h>
#include <libplacebo/renderer.h>
#define PL_LIBAV_IMPLEMENTATION 0
#include <libplacebo/utils/libav.h>

extern "C" {
#include <libavutil/pixdesc.h>
}

#include "ffcommon.h"

namespace {

constexpr int kPlaneCount = 3;
constexpr int kTextureSetCount = 3;

pl_rotation MapRotation(int rotation_degrees) {
  switch (rotation_degrees) {
    case 90:
      return PL_ROTATION_90;
    case 180:
      return PL_ROTATION_180;
    case 270:
      return PL_ROTATION_270;
    default:
      return PL_ROTATION_0;
  }
}

pl_color_space GetTargetColorSpace(PlaceboOutputColorMode color_mode) {
  switch (color_mode) {
    case PlaceboOutputColorMode::kBt2020Pq:
      return pl_color_space_hdr10;
    case PlaceboOutputColorMode::kBt2020Hlg:
      return pl_color_space_bt2020_hlg;
    case PlaceboOutputColorMode::kSdr:
      return pl_color_space_srgb;
  }
  return pl_color_space_srgb;
}

void PlaceboLog(void *, pl_log_level level, const char *message) {
  int android_level;
  switch (level) {
    case PL_LOG_FATAL:
    case PL_LOG_ERR:
      android_level = ANDROID_LOG_ERROR;
      break;
    case PL_LOG_WARN:
      android_level = ANDROID_LOG_WARN;
      break;
    case PL_LOG_INFO:
      android_level = ANDROID_LOG_INFO;
      break;
    default:
      android_level = ANDROID_LOG_DEBUG;
      break;
  }
  __android_log_print(android_level, LOG_TAG, "libplacebo: %s", message);
}

pl_voidfunc_t GetGlProcAddress(const char *name) {
  return reinterpret_cast<pl_voidfunc_t>(eglGetProcAddress(name));
}

}  // namespace

bool CanMapDolbyVisionMetadata(const AVFrame *frame) {
  if (!frame) {
    return false;
  }
  pl_dovi_metadata metadata = {};
  pl_color_repr representation = {};
  pl_color_space color_space = {};
  return pl_map_avframe_dovi_metadata(&color_space, &representation, &metadata,
                                      frame);
}

class PlaceboVideoRenderer::Impl {
 public:
  ~Impl() { Shutdown(); }

  bool Initialize(void *egl_display, void *egl_context) {
    if (opengl_) {
      return egl_display_ == egl_display && egl_context_ == egl_context;
    }
    if (!egl_display || !egl_context ||
        eglGetCurrentDisplay() != static_cast<EGLDisplay>(egl_display) ||
        eglGetCurrentContext() != static_cast<EGLContext>(egl_context)) {
      LOGE("libplacebo initialization requires the presentation EGL context.");
      return false;
    }
    pl_log_params log_parameters = {};
    log_parameters.log_cb = PlaceboLog;
    log_parameters.log_level = PL_LOG_WARN;
    log_ = pl_log_create(PL_API_VER, &log_parameters);
    if (!log_) {
      return false;
    }
    pl_opengl_params opengl_parameters = {};
    opengl_parameters.get_proc_addr = GetGlProcAddress;
    opengl_parameters.egl_display = egl_display;
    opengl_parameters.egl_context = egl_context;
    opengl_ = pl_opengl_create(log_, &opengl_parameters);
    if (!opengl_) {
      Shutdown();
      return false;
    }
    renderer_ = pl_renderer_create(log_, opengl_->gpu);
    if (!renderer_) {
      Shutdown();
      return false;
    }
    egl_display_ = egl_display;
    egl_context_ = egl_context;
    return true;
  }

  bool Render(const AVFrame *frame, const PlaceboTexturePlane planes[3],
              int texture_set_index, int target_width, int target_height,
              int rotation_degrees, PlaceboOutputColorMode output_color_mode,
              bool apply_dolby_vision_mapping) {
    if (!renderer_ || !frame || !planes || target_width <= 0 ||
        target_height <= 0 || texture_set_index < 0 ||
        texture_set_index >= kTextureSetCount ||
        eglGetCurrentContext() != static_cast<EGLContext>(egl_context_)) {
      return false;
    }
    const AVPixFmtDescriptor *pixel_format =
        av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
    if (!pixel_format || pixel_format->nb_components != kPlaneCount) {
      return false;
    }
    for (int plane = 0; plane < kPlaneCount; ++plane) {
      if (!EnsureSourceTexture(texture_set_index, plane, planes[plane])) {
        return false;
      }
    }
    if (!EnsureTargetTexture(target_width, target_height)) {
      return false;
    }

    pl_frame source;
    pl_frame_from_avframe(&source, frame);
    if (source.num_planes != kPlaneCount) {
      return false;
    }
    source.crop = {0.0f, 0.0f, static_cast<float>(frame->width),
                   static_cast<float>(frame->height)};
    source.rotation = MapRotation(rotation_degrees);
    source.repr.alpha = PL_ALPHA_NONE;
    source.repr.bits.sample_depth = planes[0].internal_format == GL_R8 ? 8 : 16;
    source.repr.bits.color_depth = pixel_format->comp[0].depth;

    pl_dovi_metadata dolby_vision = {};
    if (apply_dolby_vision_mapping &&
        !pl_map_avframe_dovi_metadata(&source.color, &source.repr,
                                      &dolby_vision, frame)) {
      LOGE("Invalid FFmpeg Dolby Vision mapping metadata.");
      return false;
    }
    const bool has_enhancement_residual = dolby_vision.nlq_active;
    dolby_vision.nlq_active = false;
    if (has_enhancement_residual && !base_layer_fallback_logged_) {
      __android_log_print(
          ANDROID_LOG_INFO, LOG_TAG,
          "Dolby Vision enhancement residual is unavailable; rendering "
          "validated base-layer reshaping only.");
      base_layer_fallback_logged_ = true;
    }

    for (int plane = 0; plane < kPlaneCount; ++plane) {
      source.planes[plane].texture =
          source_textures_[texture_set_index][plane].texture;
    }

    if (target_texture_->params.format->num_components < 3) {
      LOGE("libplacebo target framebuffer has fewer than three components.");
      return false;
    }
    const int target_components = 3;
    pl_frame target = {};
    target.num_planes = 1;
    target.planes[0].texture = target_texture_;
    target.planes[0].flipped = true;
    target.planes[0].components = target_components;
    target.planes[0].component_mapping[0] = 0;
    target.planes[0].component_mapping[1] = 1;
    target.planes[0].component_mapping[2] = 2;
    target.planes[0].component_mapping[3] = PL_CHANNEL_NONE;
    target.crop = {0.0f, 0.0f, static_cast<float>(target_width),
                   static_cast<float>(target_height)};
    target.repr.sys = PL_COLOR_SYSTEM_RGB;
    target.repr.levels = PL_COLOR_LEVELS_FULL;
    target.repr.alpha = PL_ALPHA_NONE;
    target.repr.bits.sample_depth =
        target_texture_->params.format->component_depth[0];
    target.repr.bits.color_depth = target.repr.bits.sample_depth;
    target.color = GetTargetColorSpace(output_color_mode);

    if (!pl_render_image(renderer_, &source, &target, &pl_render_fast_params)) {
      LOGE("libplacebo OpenGL rendering failed.");
      return false;
    }
    return true;
  }

  void Flush() {
    if (renderer_) {
      pl_renderer_flush_cache(renderer_);
    }
  }

  void ResetSurface() {
    if (opengl_ && target_texture_) {
      pl_tex_destroy(opengl_->gpu, &target_texture_);
    }
    target_width_ = 0;
    target_height_ = 0;
  }

  void Shutdown() {
    if (!opengl_ && !log_) {
      return;
    }
    if (egl_context_ &&
        eglGetCurrentContext() != static_cast<EGLContext>(egl_context_)) {
      LOGE("Refusing to destroy libplacebo without its EGL context current.");
      return;
    }
    ResetSurface();
    if (renderer_) {
      pl_renderer_destroy(&renderer_);
    }
    if (opengl_) {
      for (auto &texture_set : source_textures_) {
        for (WrappedTexture &source_texture : texture_set) {
          if (source_texture.texture) {
            pl_tex_destroy(opengl_->gpu, &source_texture.texture);
          }
          source_texture = {};
        }
      }
      pl_opengl_destroy(&opengl_);
    }
    if (log_) {
      pl_log_destroy(&log_);
    }
    egl_display_ = nullptr;
    egl_context_ = nullptr;
  }

  void Abandon() {
    log_ = nullptr;
    opengl_ = nullptr;
    renderer_ = nullptr;
    for (auto &texture_set : source_textures_) {
      for (WrappedTexture &source_texture : texture_set) {
        source_texture = {};
      }
    }
    target_texture_ = nullptr;
    target_width_ = 0;
    target_height_ = 0;
    egl_display_ = nullptr;
    egl_context_ = nullptr;
  }

 private:
  struct WrappedTexture {
    pl_tex texture = nullptr;
    PlaceboTexturePlane descriptor;
  };

  bool EnsureSourceTexture(int texture_set_index, int plane,
                           const PlaceboTexturePlane &descriptor) {
    if (!descriptor.texture || descriptor.width <= 0 ||
        descriptor.height <= 0 || !descriptor.internal_format) {
      return false;
    }
    WrappedTexture &wrapped = source_textures_[texture_set_index][plane];
    if (wrapped.texture && wrapped.descriptor.texture == descriptor.texture &&
        wrapped.descriptor.width == descriptor.width &&
        wrapped.descriptor.height == descriptor.height &&
        wrapped.descriptor.internal_format == descriptor.internal_format) {
      return true;
    }
    if (wrapped.texture) {
      pl_tex_destroy(opengl_->gpu, &wrapped.texture);
    }
    wrapped.descriptor = descriptor;
    pl_opengl_wrap_params wrap_parameters = {};
    wrap_parameters.texture = descriptor.texture;
    wrap_parameters.width = descriptor.width;
    wrap_parameters.height = descriptor.height;
    wrap_parameters.target = GL_TEXTURE_2D;
    wrap_parameters.iformat = descriptor.internal_format;
    wrapped.texture = pl_opengl_wrap(opengl_->gpu, &wrap_parameters);
    if (!wrapped.texture || !wrapped.texture->params.sampleable) {
      LOGE("Failed to wrap FFmpeg plane %d for libplacebo.", plane);
      if (wrapped.texture) {
        pl_tex_destroy(opengl_->gpu, &wrapped.texture);
      }
      wrapped.descriptor = {};
      return false;
    }
    return true;
  }

  bool EnsureTargetTexture(int width, int height) {
    if (target_texture_ && target_width_ == width && target_height_ == height) {
      return true;
    }
    ResetSurface();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    pl_opengl_wrap_params wrap_parameters = {};
    wrap_parameters.width = width;
    wrap_parameters.height = height;
    target_texture_ = pl_opengl_wrap(opengl_->gpu, &wrap_parameters);
    if (!target_texture_ || !target_texture_->params.renderable) {
      LOGE("Failed to wrap the Android default framebuffer for libplacebo.");
      ResetSurface();
      return false;
    }
    target_width_ = width;
    target_height_ = height;
    return true;
  }

  pl_log log_ = nullptr;
  pl_opengl opengl_ = nullptr;
  pl_renderer renderer_ = nullptr;
  WrappedTexture source_textures_[kTextureSetCount][kPlaneCount];
  pl_tex target_texture_ = nullptr;
  int target_width_ = 0;
  int target_height_ = 0;
  void *egl_display_ = nullptr;
  void *egl_context_ = nullptr;
  bool base_layer_fallback_logged_ = false;
};

PlaceboVideoRenderer::PlaceboVideoRenderer()
    : impl_(std::make_unique<Impl>()) {}

PlaceboVideoRenderer::~PlaceboVideoRenderer() = default;

bool PlaceboVideoRenderer::Initialize(void *egl_display, void *egl_context) {
  return impl_->Initialize(egl_display, egl_context);
}

bool PlaceboVideoRenderer::Render(const AVFrame *frame,
                                  const PlaceboTexturePlane planes[3],
                                  int texture_set_index, int target_width,
                                  int target_height, int rotation_degrees,
                                  PlaceboOutputColorMode output_color_mode,
                                  bool apply_dolby_vision_mapping) {
  return impl_->Render(frame, planes, texture_set_index, target_width,
                       target_height, rotation_degrees, output_color_mode,
                       apply_dolby_vision_mapping);
}

void PlaceboVideoRenderer::Flush() { impl_->Flush(); }

void PlaceboVideoRenderer::Shutdown() { impl_->Shutdown(); }

void PlaceboVideoRenderer::Abandon() { impl_->Abandon(); }
