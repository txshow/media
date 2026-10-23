/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package is.xyz.mpv;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestrictTo(LIBRARY_GROUP)
public final class MPVLib {

  private static final CopyOnWriteArrayList<EventObserver> OBSERVERS = new CopyOnWriteArrayList<>();
  private static final CopyOnWriteArrayList<LogObserver> LOG_OBSERVERS =
      new CopyOnWriteArrayList<>();
  /** Registered custom-protocol byte streams, keyed by their URL. Java-side registry (official). */
  private static final ConcurrentHashMap<String, StreamCallback> STREAMS =
      new ConcurrentHashMap<>();

  public static native void create(Context appctx);

  public static native void init();

  public static native int destroy();

  public static native void attachSurface(Surface surface);

  public static native void replaceSurface(Surface surface);

  public static native void detachSurface();

  public static native void attachOsdSurface(Surface surface);

  public static native void replaceOsdSurface(Surface surface);

  public static native void detachOsdSurface();

  /** Queues a command whose completion is delivered to {@link EventObserver#eventCommandReply}. */
  public static native int enqueueCommand(long requestId, String[] cmd);

  /**
   * Queues a command whose completion carries a long string result, delivered to {@link
   * EventObserver#eventCommandReply}. Declared in the official Java bridge; implemented by the
   * native libplayer.
   */
  public static native int enqueueCommandLongResult(long requestId, String arg, String[] cmd);

  public static native int setOptionString(String name, String value);

  public static native Integer getPropertyInt(String property);

  public static native Double getPropertyDouble(String property);

  public static native Boolean getPropertyBoolean(String property);

  public static native String getPropertyString(String property);

  public static native @Nullable byte[] getPropertyByteArray(String property);

  public static native int observeProperty(String property, int format);

  public static void addObserver(EventObserver observer) {
    OBSERVERS.addIfAbsent(observer);
  }

  public static void removeObserver(EventObserver observer) {
    OBSERVERS.remove(observer);
  }

  public static void eventProperty(String property, long value) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventProperty(property, value);
    }
  }

  public static void eventProperty(String property, boolean value) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventProperty(property, value);
    }
  }

  public static void eventProperty(String property, double value) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventProperty(property, value);
    }
  }

  public static void eventProperty(String property, String value) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventProperty(property, value);
    }
  }

  public static void eventProperty(String property) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventProperty(property);
    }
  }

  public static void event(int eventId) {
    for (EventObserver observer : OBSERVERS) {
      observer.event(eventId);
    }
  }

  public static void eventCommandReply(long requestId, int error, long result) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventCommandReply(requestId, error, result);
    }
  }

  public static void eventEndFile(int reason, int error, @Nullable String errorString) {
    for (EventObserver observer : OBSERVERS) {
      observer.eventEndFile(reason, error, errorString);
    }
  }

  public static void addLogObserver(LogObserver observer) {
    LOG_OBSERVERS.addIfAbsent(observer);
  }

  public static void removeLogObserver(LogObserver observer) {
    LOG_OBSERVERS.remove(observer);
  }

  public static void logMessage(String prefix, int level, String text) {
    for (LogObserver observer : LOG_OBSERVERS) {
      observer.logMessage(prefix, level, text);
    }
  }

  /**
   * Registers a custom-protocol byte stream provider under {@code url}. Called by the application
   * before opening the stream in mpv. Java-side registry (official: {@code registerStream}).
   */
  public static void registerStream(String url, StreamCallback callback) {
    STREAMS.put(url, callback);
  }

  /**
   * Opens a previously registered custom-protocol byte stream. Returns {@code null} if no provider
   * is registered for {@code url}, otherwise wraps the provider as a {@link Stream}. Java-side
   * implementation mirroring the official {@code openStream} (registry lookup + wrapper).
   */
  public static Stream openStream(String url) {
    StreamCallback callback = STREAMS.get(url);
    if (callback == null) {
      return null;
    }
    return new Stream() {
      @Override
      public int read(ByteBuffer buffer) {
        return callback.read(buffer);
      }

      @Override
      public long seek(long offset) {
        return callback.seek(offset);
      }

      @Override
      public long size() {
        return callback.size();
      }

      @Override
      public void cancel() {
        callback.cancel();
      }

      @Override
      public void close() {
        callback.close();
      }
    };
  }

  /** Unregisters a previously registered byte stream. Java-side registry removal (official). */
  public static void unregisterStream(String url) {
    STREAMS.remove(url);
  }

  public interface EventObserver {

    void eventProperty(String property);

    void eventProperty(String property, long value);

    void eventProperty(String property, boolean value);

    void eventProperty(String property, String value);

    void eventProperty(String property, double value);

    void event(int eventId);

    default void eventCommandReply(long requestId, int error, long result) {}

    void eventEndFile(int reason, int error, @Nullable String errorString);
  }

  public interface LogObserver {

    void logMessage(String prefix, int level, String text);
  }

  /**
   * Byte-stream provider registered by the application for custom-protocol URLs (official marker
   * interface {@code is.xyz.mpv.a}). {@link #openStream(String)} wraps a registered provider into
   * a {@link Stream} that forwards every call to this interface.
   */
  public interface StreamCallback {

    /** Reads up to {@code buffer.remaining()} bytes into {@code buffer}; returns bytes read or -1 at EOF. */
    int read(ByteBuffer buffer);

    /** Seeks to an absolute byte {@code offset}; returns the resulting offset or -1 on failure. */
    long seek(long offset);

    /** Returns the total size in bytes of the stream. */
    long size();

    /** Cancels a pending read, unblocking it. */
    void cancel();

    /** Closes the stream. */
    void close();
  }

  /** Byte-stream provider used by the native bridge for custom protocol reads. */
  public interface Stream {

    /** Reads up to {@code buffer.remaining()} bytes into {@code buffer}; returns bytes read or -1 at EOF. */
    int read(ByteBuffer buffer);

    /** Seeks to an absolute byte {@code offset}; returns the resulting offset or -1 on failure. */
    long seek(long offset);

    /** Returns the total size in bytes of the stream. */
    long size();

    /** Cancels a pending read, unblocking it. */
    void cancel();

    /** Closes the stream. */
    void close();
  }

  public static final class MpvFormat {

    public static final int MPV_FORMAT_NONE = 0;
    public static final int MPV_FORMAT_FLAG = 3;
    public static final int MPV_FORMAT_INT64 = 4;
    public static final int MPV_FORMAT_DOUBLE = 5;
  }

  public static final class MpvEvent {

    public static final int MPV_EVENT_SHUTDOWN = 1;
    public static final int MPV_EVENT_START_FILE = 6;
    public static final int MPV_EVENT_END_FILE = 7;
    public static final int MPV_EVENT_FILE_LOADED = 8;
    public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
    public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
    public static final int MPV_EVENT_SEEK = 20;
    public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
  }

  public static final class MpvEndFileReason {

    public static final int MPV_END_FILE_REASON_EOF = 0;
    public static final int MPV_END_FILE_REASON_STOP = 2;
    public static final int MPV_END_FILE_REASON_QUIT = 3;
    public static final int MPV_END_FILE_REASON_ERROR = 4;
    public static final int MPV_END_FILE_REASON_REDIRECT = 5;
  }

  public static final class MpvError {

    public static final int MPV_ERROR_SUCCESS = 0;
    public static final int MPV_ERROR_EVENT_QUEUE_FULL = -1;
    public static final int MPV_ERROR_NOMEM = -2;
    public static final int MPV_ERROR_UNINITIALIZED = -3;
    public static final int MPV_ERROR_INVALID_PARAMETER = -4;
    public static final int MPV_ERROR_OPTION_NOT_FOUND = -5;
    public static final int MPV_ERROR_OPTION_FORMAT = -6;
    public static final int MPV_ERROR_OPTION_ERROR = -7;
    public static final int MPV_ERROR_PROPERTY_NOT_FOUND = -8;
    public static final int MPV_ERROR_PROPERTY_FORMAT = -9;
    public static final int MPV_ERROR_PROPERTY_UNAVAILABLE = -10;
    public static final int MPV_ERROR_PROPERTY_ERROR = -11;
    public static final int MPV_ERROR_COMMAND = -12;
    public static final int MPV_ERROR_LOADING_FAILED = -13;
    public static final int MPV_ERROR_AO_INIT_FAILED = -14;
    public static final int MPV_ERROR_VO_INIT_FAILED = -15;
    public static final int MPV_ERROR_NOTHING_TO_PLAY = -16;
    public static final int MPV_ERROR_UNKNOWN_FORMAT = -17;
    public static final int MPV_ERROR_UNSUPPORTED = -18;
    public static final int MPV_ERROR_NOT_IMPLEMENTED = -19;
    public static final int MPV_ERROR_GENERIC = -20;
  }
}
