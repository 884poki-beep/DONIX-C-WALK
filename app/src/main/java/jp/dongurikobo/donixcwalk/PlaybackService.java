package jp.dongurikobo.donixcwalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

/**
 * Real Android audio engine. MediaPlayer and its foreground service continue
 * playing when the WebView is backgrounded, throttled, or its Activity closes.
 */
public class PlaybackService extends Service {
  static final String PREPARING = "jp.dongurikobo.donixcwalk.PREPARING";
  static final String START_TRACK = "jp.dongurikobo.donixcwalk.START_TRACK";
  static final String START_PLAY = "jp.dongurikobo.donixcwalk.START_PLAY";
  static final String PAUSE = "jp.dongurikobo.donixcwalk.PAUSE";
  static final String TOGGLE = "jp.dongurikobo.donixcwalk.TOGGLE";
  static final String NEXT = "jp.dongurikobo.donixcwalk.NEXT";
  static final String PREVIOUS = "jp.dongurikobo.donixcwalk.PREVIOUS";
  static final String SELECT_TRACK = "jp.dongurikobo.donixcwalk.SELECT_TRACK";
  static final String STOP = "jp.dongurikobo.donixcwalk.STOP";
  static final String EXTRA_TRACK_ID = "track_id";
  static final String CHANNEL = "donix_cwalk_native_playback";
  private static final int NOTIFICATION_ID = 201;

  public interface Listener { void onPlaybackStateChanged(String json); }
  public class LocalBinder extends Binder { PlaybackService getService() { return PlaybackService.this; } }
  private final IBinder binder = new LocalBinder();
  private Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Random random = new Random();
  private final ArrayList<Track> queue = new ArrayList<>();
  private final ArrayList<String> allowed = new ArrayList<>();
  private final HashMap<String, Bitmap> artworkCache = new HashMap<>();
  private MediaPlayer player;
  private MediaSession session;
  private NotificationManager notificationManager;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;
  private boolean hasAudioFocus;
  private boolean restoreAfterFocusGain;
  private boolean inForeground;
  private boolean prepared;
  private boolean preparing;
  private boolean wantsToPlay;
  private boolean playing;
  private boolean shuffle;
  private boolean repeat;
  private boolean ended;
  private float volume = 0.8f;
  private String currentId = "";
  private String currentTitle = "DONIX C-WALK";
  private String currentArtist = "dongurikobo";
  private String error = "";
  private Bitmap currentArt;
  private int durationMs;
  private int positionMs;
  private int generation;
  private String publishedTitle;
  private String publishedArtist;
  private String publishedId;
  private Bitmap publishedArtwork;
  private int publishedDuration = -1;
  private volatile String snapshot = "{\"id\":\"\",\"playing\":false,\"position\":0,\"duration\":0}";

  private static class Track {
    String id, name, artist;
    Track(String id, String name, String artist) { this.id = id; this.name = name; this.artist = artist; }
  }

  private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
    }
  };

  private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
    if (change == AudioManager.AUDIOFOCUS_GAIN) {
      hasAudioFocus = true;
      if (restoreAfterFocusGain) {
        restoreAfterFocusGain = false;
        if (!currentId.isEmpty()) {
          ensureForeground();
          wantsToPlay = true;
          resumePlayback();
        }
      }
    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
      restoreAfterFocusGain = playing;
      pauseInternal(false);
    } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
      restoreAfterFocusGain = false;
      pauseInternal(false);
    }
  };

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      if (playing && player != null) {
        try { positionMs = player.getCurrentPosition(); } catch (IllegalStateException ignored) { }
        publish(false);
      }
      handler.postDelayed(this, 1000);
    }
  };

  @Override public void onCreate() {
    super.onCreate();
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.createNotificationChannel(new NotificationChannel(
        CHANNEL, "DONIX C-WALK 再生", NotificationManager.IMPORTANCE_LOW));
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    AudioAttributes attrs = new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA).build();
    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build();
    session = new MediaSession(this, "DONIX C-WALK Background Player");
    session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setCallback(new MediaSession.Callback() {
      @Override public void onPlay() { ensureForeground(); play(); }
      @Override public void onPause() { pause(); }
      @Override public void onSkipToNext() { skip(1, false); }
      @Override public void onSkipToPrevious() { skip(-1, false); }
      @Override public void onSeekTo(long pos) { seek((int) pos); }
      @Override public void onStop() { stopPlayback(); }
    });
    session.setActive(true);
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
          Context.RECEIVER_NOT_EXPORTED);
    } else {
      registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    handler.post(ticker);
    publish(true);
  }

  @Override public IBinder onBind(Intent intent) { return binder; }
  @Override public boolean onUnbind(Intent intent) { listener = null; return true; }
  @Override public void onRebind(Intent intent) { super.onRebind(intent); }

  void setListener(Listener l) {
    listener = l;
    if (l != null) l.onPlaybackStateChanged(snapshot);
  }
  String getSnapshot() { return snapshot; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_STICKY;
    String action = intent.getAction();
    if (STOP.equals(action)) { stopPlayback(); stopSelf(); return START_NOT_STICKY; }
    if (PREPARING.equals(action)) {
      ensureForeground();
    } else if (START_TRACK.equals(action)) {
      ensureForeground();
      selectTrack(intent.getStringExtra(EXTRA_TRACK_ID), true);
    } else if (START_PLAY.equals(action)) {
      ensureForeground();
      play();
    } else if (PAUSE.equals(action)) {
      pause();
    } else if (TOGGLE.equals(action)) {
      if (playing || (preparing && wantsToPlay)) pause();
      else { ensureForeground(); play(); }
    } else if (NEXT.equals(action)) {
      ensureForeground(); skip(1, false);
    } else if (PREVIOUS.equals(action)) {
      ensureForeground(); skip(-1, false);
    } else if (SELECT_TRACK.equals(action)) {
      selectTrack(intent.getStringExtra(EXTRA_TRACK_ID), false);
    }
    return START_STICKY;
  }

  void setQueue(String json) {
    try {
      JSONObject parsed = new JSONObject(json);
      JSONArray songs = parsed.getJSONArray("tracks");
      queue.clear();
      for (int i = 0; i < songs.length(); i++) {
        JSONObject t = songs.getJSONObject(i);
        String id = t.optString("id");
        if (!id.isEmpty()) queue.add(new Track(id, t.optString("name", "CASSETTE"), t.optString("artist", "dongurikobo")));
      }
      allowed.clear();
      JSONArray a = parsed.optJSONArray("allowed");
      if (a != null) for (int i = 0; i < a.length(); i++) allowed.add(a.optString(i));
      if (allowed.isEmpty()) for (Track t : queue) allowed.add(t.id);
      shuffle = parsed.optBoolean("shuffle", false);
      repeat = parsed.optBoolean("repeat", false);
      if (!currentId.isEmpty()) {
        currentTitle = titleFor(currentId);
        currentArtist = artistFor(currentId);
        publish(true);
      }
    } catch (Exception e) { Log.w("DONIX", "Bad playlist data", e); }
  }

  private String titleFor(String id) {
    for (Track t : queue) if (t.id.equals(id)) return t.name;
    return currentId.equals(id) ? currentTitle : "CASSETTE";
  }

  private String artistFor(String id) {
    for (Track t : queue) if (t.id.equals(id)) return t.artist;
    return "dongurikobo";
  }

  void selectTrack(String id, boolean autoplay) {
    if (id == null || id.isEmpty()) return;
    if (id.equals(currentId) && player != null) {
      if (autoplay) { wantsToPlay = true; resumePlayback(); }
      return;
    }
    releasePlayer();
    currentId = id;
    currentTitle = titleFor(id);
    currentArtist = artistFor(id);
    currentArt = artworkCache.get(id);
    if (currentArt == null) loadArtworkAsync(id);
    wantsToPlay = autoplay;
    preparing = true;
    error = "";
    ended = false;
    positionMs = 0;
    durationMs = 0;
    publish(true);
    try {
      String ref = getSharedPreferences("donix_native_tracks", MODE_PRIVATE)
          .getString("song:" + id, null);
      if (ref == null) throw new IllegalArgumentException("Audio source is missing");
      MediaPlayer mp = new MediaPlayer();
      player = mp;
      int thisGeneration = generation;
      mp.setAudioAttributes(new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
      mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
      mp.setVolume(volume, volume);
      mp.setOnPreparedListener(p -> {
        if (player != p || generation != thisGeneration) return;
        prepared = true;
        preparing = false;
        try { durationMs = p.getDuration(); } catch (Exception ignored) { }
        if (wantsToPlay) resumePlayback();
        else { publish(true); dropForeground(); }
      });
      mp.setOnCompletionListener(p -> {
        if (p != player) return;
        positionMs = durationMs;
        playing = false;
        wantsToPlay = false;
        ended = true;
        if (repeat) {
          try { p.seekTo(0); } catch (Exception ignored) { }
          wantsToPlay = true;
          resumePlayback();
        } else skip(1, true);
      });
      mp.setOnErrorListener((p, what, extra) -> {
        if (p == player) {
          Log.w("DONIX", "MediaPlayer error " + what + "/" + extra);
          error = "この曲を再生できませんでした。ファイルを選び直してください。";
          releasePlayer();
          abandonAudioFocus();
          publish(true);
          dropForeground();
        }
        return true;
      });
      if (ref.startsWith("uri:")) {
        mp.setDataSource(this, Uri.parse(ref.substring(4)));
      } else if (ref.startsWith("file:")) {
        String name = ref.substring(5);
        File f = new File(new File(getFilesDir(), "native_audio"), name);
        if (!f.isFile()) throw new IllegalArgumentException("Cached file was removed");
        mp.setDataSource(f.getAbsolutePath());
      } else {
        throw new IllegalArgumentException("Invalid source");
      }
      mp.prepareAsync();
    } catch (Exception e) {
      Log.w("DONIX", "Could not open " + id, e);
      releasePlayer();
      abandonAudioFocus();
      error = "音楽ファイルを開けません。アプリから曲を選び直してください。";
      publish(true);
      dropForeground();
    }
  }

  void play() {
    if (currentId.isEmpty() && !queue.isEmpty()) {
      selectTrack(queue.get(0).id, true);
      return;
    }
    wantsToPlay = true;
    if (player == null && !currentId.isEmpty()) selectTrack(currentId, true);
    else resumePlayback();
  }

  private boolean requestAudioFocus() {
    if (hasAudioFocus) return true;
    int result = audioManager.requestAudioFocus(focusRequest);
    hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    return hasAudioFocus;
  }

  private void resumePlayback() {
    if (!prepared || player == null) { publish(true); return; }
    if (!requestAudioFocus()) { wantsToPlay = false; publish(true); return; }
    try {
      if (ended) player.seekTo(0);
      ended = false;
      player.start();
      playing = true;
      wantsToPlay = true;
      error = "";
      ensureForeground();
      publish(true);
    } catch (Exception e) {
      Log.w("DONIX", "Could not start playback", e);
      playing = false;
      wantsToPlay = false;
      error = "再生を開始できませんでした。";
      publish(true);
      dropForeground();
    }
  }

  void pause() { restoreAfterFocusGain = false; pauseInternal(true); }
  private void pauseInternal(boolean abandonFocus) {
    wantsToPlay = false;
    if (player != null && playing) {
      try { positionMs = player.getCurrentPosition(); player.pause(); }
      catch (IllegalStateException ignored) { }
    }
    playing = false;
    if (abandonFocus) abandonAudioFocus();
    publish(true);
    dropForeground();
  }
  private void abandonAudioFocus() {
    if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest);
    hasAudioFocus = false;
  }

  void seek(int millis) {
    if (player == null || !prepared) return;
    positionMs = Math.max(0, Math.min(millis, durationMs));
    try { player.seekTo(positionMs); } catch (IllegalStateException ignored) { }
    publish(true);
  }
  void setVolume(float v) {
    volume = Math.max(0f, Math.min(v, 1f));
    if (player != null) player.setVolume(volume, volume);
  }
  void setArtwork(String id, String base64) {
    if (id == null || base64 == null || base64.length() > 3_000_000 ||
        !base64.startsWith("data:image/")) return;
    try {
      int comma = base64.indexOf(',');
      if (comma < 0) return;
      byte[] bytes = Base64.decode(base64.substring(comma + 1), Base64.DEFAULT);
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inSampleSize = 2;
      Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
      if (bm == null) return;
      if (artworkCache.size() >= 12) artworkCache.clear();
      artworkCache.put(id, bm);
      if (id.equals(currentId)) { currentArt = bm; publish(true); }
    } catch (Exception e) { Log.w("DONIX", "Cover art decode failed", e); }
  }

  private void loadArtworkAsync(String id) {
    String source = getSharedPreferences("donix_native_tracks", MODE_PRIVATE)
        .getString("song:" + id, null);
    if (source == null) return;
    new Thread(() -> {
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        if (source.startsWith("uri:")) r.setDataSource(this, Uri.parse(source.substring(4)));
        else if (source.startsWith("file:")) r.setDataSource(new File(
            new File(getFilesDir(), "native_audio"), source.substring(5)).getAbsolutePath());
        byte[] data = r.getEmbeddedPicture();
        if (data != null && data.length < 4_000_000) {
          BitmapFactory.Options opts = new BitmapFactory.Options();
          opts.inSampleSize = 2;
          Bitmap artwork = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
          if (artwork != null) handler.post(() -> {
            if (artworkCache.size() >= 12) artworkCache.clear();
            artworkCache.put(id, artwork);
            if (id.equals(currentId)) { currentArt = artwork; publish(true); }
          });
        }
      } catch (Exception ignored) { }
      finally { try { r.release(); } catch (Exception ignored) { } }
    }, "DONIX-artwork").start();
  }

  void skip(int direction, boolean automatic) {
    if (allowed.isEmpty()) {
      if (automatic) finishPlaylist();
      return;
    }
    // C-WALK historically loops to the start of the cassette playlist.
    // The Android engine must advance without asking the paused WebView.
    int pos = allowed.indexOf(currentId);
    int next;
    if (shuffle && allowed.size() > 1) {
      next = (pos + 1 + random.nextInt(allowed.size() - 1)) % allowed.size();
    } else {
      next = ((pos + direction) % allowed.size() + allowed.size()) % allowed.size();
    }
    selectTrack(allowed.get(next), true);
  }
  private void finishPlaylist() {
    wantsToPlay = false;
    playing = false;
    ended = true;
    abandonAudioFocus();
    publish(true);
    dropForeground();
  }

  void stopPlayback() {
    releasePlayer();
    currentId = "";
    currentTitle = "DONIX C-WALK";
    currentArtist = "dongurikobo";
    currentArt = null;
    playing = false;
    preparing = false;
    wantsToPlay = false;
    ended = false;
    durationMs = 0;
    positionMs = 0;
    abandonAudioFocus();
    notificationManager.cancel(NOTIFICATION_ID);
    if (inForeground) stopForeground(STOP_FOREGROUND_REMOVE);
    inForeground = false;
    publish(false);
  }
  private void releasePlayer() {
    ++generation;
    prepared = false;
    preparing = false;
    playing = false;
    if (player != null) {
      try { player.reset(); } catch (Exception ignored) { }
      try { player.release(); } catch (Exception ignored) { }
      player = null;
    }
  }

  private PendingIntent control(String action, int requestCode) {
    return PendingIntent.getForegroundService(this, requestCode,
        new Intent(this, PlaybackService.class).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  private Notification buildNotification() {
    Intent open = new Intent(this, MainActivity.class)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content = PendingIntent.getActivity(this, 1, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    boolean active = playing || (preparing && wantsToPlay);
    Notification.Builder b = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(currentTitle)
        .setContentText(currentArtist + (preparing ? " · 読み込み中" : ""))
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(content)
        .setShowWhen(false)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,
            "前の曲", control(PREVIOUS, 2)).build())
        .addAction(new Notification.Action.Builder(
            active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            active ? "一時停止" : "再生", control(TOGGLE, 3)).build())
        .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,
            "次の曲", control(NEXT, 4)).build())
        .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
    if (currentArt != null) b.setLargeIcon(currentArt);
    return b.build();
  }
  private void ensureForeground() {
    if (currentId.isEmpty()) currentTitle = "DONIX C-WALK";
    try {
      startForeground(NOTIFICATION_ID, buildNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      inForeground = true;
    } catch (Exception e) {
      Log.e("DONIX", "Unable to enter media foreground service", e);
    }
  }
  private void dropForeground() {
    if (!inForeground) return;
    stopForeground(STOP_FOREGROUND_DETACH);
    inForeground = false;
    if (!currentId.isEmpty()) notificationManager.notify(NOTIFICATION_ID, buildNotification());
  }

  private void publish(boolean updateNotification) {
    long position = positionMs;
    if (playing && player != null) {
      try { position = player.getCurrentPosition(); } catch (Exception ignored) { }
    }
    int state = preparing ? PlaybackState.STATE_BUFFERING :
        playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
    if (!currentId.equals(publishedId) || !currentTitle.equals(publishedTitle) ||
        !currentArtist.equals(publishedArtist) ||
        durationMs != publishedDuration || currentArt != publishedArtwork) {
      MediaMetadata.Builder meta = new MediaMetadata.Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
          .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
          .putString(MediaMetadata.METADATA_KEY_ALBUM, "CASSETTE")
          .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, durationMs));
      if (currentArt != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArt);
      session.setMetadata(meta.build());
      publishedId = currentId;
      publishedTitle = currentTitle;
      publishedArtist = currentArtist;
      publishedArtwork = currentArt;
      publishedDuration = durationMs;
    }
    session.setPlaybackState(new PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
            PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT |
            PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
        .setState(state, position, playing ? 1f : 0f).build());
    try {
      JSONObject status = new JSONObject();
      status.put("id", currentId);
      status.put("playing", playing || (preparing && wantsToPlay));
      status.put("buffering", preparing);
      status.put("ended", ended);
      status.put("position", position);
      status.put("duration", durationMs);
      status.put("error", error);
      snapshot = status.toString();
    } catch (Exception ignored) { }
    if (updateNotification && !currentId.isEmpty()) {
      notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }
    if (listener != null) listener.onPlaybackStateChanged(snapshot);
  }

  @Override public void onDestroy() {
    handler.removeCallbacks(ticker);
    try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
    stopPlayback();
    session.setActive(false);
    session.release();
    super.onDestroy();
  }
}
