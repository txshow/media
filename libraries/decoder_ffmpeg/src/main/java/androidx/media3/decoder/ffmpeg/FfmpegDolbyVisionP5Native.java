package androidx.media3.decoder.ffmpeg;

import android.os.Build;
import android.view.Surface;
import androidx.media3.common.util.Log;

/**
 * JNI bridge for the Dolby Vision profile-5 GPU mapping pipeline, implemented by
 * {@code libffmpegDoviJNI.so}. Ported 1:1 from the official obfuscated {@code
 * FfmpegDolbyVisionP5Native} ({@code r8-map-id-c17cc7ab...}); native method names are unchanged so
 * they resolve against the shipped native library.
 */
final class FfmpegDolbyVisionP5Native {

  private static final String TAG = "FfmpegDoviP5Native";

  /** Cached capability probe, or {@code null} until {@link #probe()} has run. */
  public static Probe a;

  private FfmpegDolbyVisionP5Native() {}

  /** A profile-5 GPU renderer created by {@link #createRenderer(int, int)}. */
  public static final class NativeRenderer implements AutoCloseable {
    public final long[] c = new long[11];
    public final Stats i = new Stats();
    public long n;
    public Surface o;

    public NativeRenderer(long j, Surface surface) {
      this.n = j;
      this.o = surface;
    }

    public final synchronized Stats a() {
      long j = this.n;
      if (j != 0 && FfmpegDolbyVisionP5Native.nativeGetStats(j, this.c)) {
        Stats.a(this.i, this.c);
        return this.i;
      }
      return Stats.l;
    }

    public final synchronized void b(Surface surface, int width, int height) {
      long j = this.n;
      if (j != 0) {
        FfmpegDolbyVisionP5Native.nativeSetOutputSurface(j, surface, width, height);
      }
    }

    @Override
    public final synchronized void close() {
      long j = this.n;
      if (j == 0) {
        return;
      }
      Surface surface = this.o;
      this.o = null;
      FfmpegDolbyVisionP5Native.nativeRelease(j);
      this.n = 0L;
      if (surface != null) {
        surface.release();
      }
    }
  }

  /** Outcome of probing the GPU pipeline capabilities. */
  public static final class Probe {
    public final boolean a;
    public final int b;
    public final String c;

    public Probe(int i, String str, boolean z) {
      this.a = z;
      this.b = i;
      this.c = str;
    }

    @Override
    public final String toString() {
      return "Probe(available=" + this.a + ", capabilities=" + this.b + ", reason=" + this.c + ")";
    }
  }

  /** Runtime statistics of the GPU pipeline. */
  public static final class Stats {
    public static final Stats l = new Stats();
    public long a;
    public long b;
    public long c;
    public long d;
    public long e;
    public long f;
    public long g;
    public long h;
    public long i;
    public long j;
    public long k;

    public static void a(Stats stats, long[] jArr) {
      stats.a = jArr[0];
      stats.b = jArr[1];
      stats.c = jArr[2];
      stats.d = jArr[3];
      stats.e = jArr[4];
      stats.f = jArr[5];
      stats.g = jArr[6];
      stats.h = jArr[7];
      stats.i = jArr[8];
      stats.j = jArr[9];
      stats.k = jArr[10];
    }

    @Override
    public final String toString() {
      return "Stats(acquired="
          + this.a
          + ", matched="
          + this.b
          + ", unmatched="
          + this.c
          + ", frameDrops="
          + this.d
          + ", pendingFrames="
          + this.e
          + ", parsedRpus="
          + this.f
          + ", malformedRpus="
          + this.g
          + ", rpuDrops="
          + this.h
          + ", pendingRpus="
          + this.i
          + ", rendered="
          + this.j
          + ", failures="
          + this.k
          + ")";
    }
  }

  public static void h(Probe probe) {
    a = probe;
    Log.i(TAG, "GPU mapping " + probe);
  }

  /** Creates a GPU pipeline for the given frame size; {@code null} if unavailable. */
  public static NativeRenderer i(int width, int height) {
    if (width <= 0 || height <= 0 || !probe().a) {
      Log.e(TAG, "Dolby Vision profile 5 renderer unavailable");
      return null;
    }
    long context = nativeCreate(width, height);
    if (context == 0) {
      Log.e(TAG, "Failed to create Dolby Vision profile 5 renderer");
      return null;
    }
    Surface surface = nativeGetInputSurface(context);
    if (surface != null) {
      return new NativeRenderer(context, surface);
    }
    nativeRelease(context);
    Log.e(TAG, "Failed to create decoder input Surface");
    return null;
  }

  /** Probes and caches the GPU pipeline capabilities. */
  public static synchronized Probe probe() {
    Probe probe = a;
    if (probe != null) {
      return probe;
    }
    if (Build.VERSION.SDK_INT < 26) {
      Probe probe2 = new Probe(0, "api-below-26", false);
      h(probe2);
      return probe2;
    }
    if (!FfmpegLibrary.isAvailable()) {
      Probe probe3 = new Probe(0, "ffmpeg-unavailable", false);
      h(probe3);
      return probe3;
    }
    try {
      System.loadLibrary("ffmpegDoviJNI");
      int capabilities = nativeProbeCapabilities();
      boolean available = (capabilities & 127) == 127;
      Probe probe4 =
          new Probe(capabilities, available ? "available" : "missing-capability", available);
      h(probe4);
      return probe4;
    } catch (LinkageError e) {
      Probe probe5 = new Probe(0, "probe-" + e.getClass().getSimpleName(), false);
      h(probe5);
      return probe5;
    } catch (RuntimeException e2) {
      Probe probe6 = new Probe(0, "probe-" + e2.getClass().getSimpleName(), false);
      h(probe6);
      return probe6;
    }
  }

  public static native void nativeClear(long j);

  private static native long nativeCreate(int i, int i2);

  private static native Surface nativeGetInputSurface(long j);

  public static native boolean nativeGetStats(long j, long[] jArr);

  private static native int nativeProbeCapabilities();

  public static native boolean nativeQueueFrame(long j, long j2, long j3);

  public static native boolean nativeQueueRpu(long j, long j2, byte[] bArr);

  public static native void nativeRedraw(long j);

  public static native void nativeRelease(long j);

  public static native void nativeSetOutputSurface(long j, Surface surface, int i, int i2);
}
