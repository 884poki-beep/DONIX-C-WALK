package jp.dongurikobo.donixcwalk;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;


import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** WebView renders the cassette player; Android's independent service plays the music. */
public class MainActivity extends Activity {
  private static final int PICK_FILES = 101;
  private static final String SOURCES = "donix_native_tracks";
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ConcurrentHashMap<String, String> covers = new ConcurrentHashMap<>();
  private final Object transferLock = new Object();
  private final Map<String, Transfer> transfers = new HashMap<>();
  private final ArrayList<ImportedFile> pendingImports = new ArrayList<>();
  private WebView web;
  private ValueCallback<Uri[]> pendingFiles;
  private volatile PlaybackService playback;
  private boolean serviceBound;
  private volatile boolean activityVisible;
  private String queuedPlaylist;

  private static class ImportedFile {
    final String name, uri;
    final long size;
    ImportedFile(String name, long size, String uri) {
      this.name = name; this.size = size; this.uri = uri;
    }
  }
  private static class Transfer {
    final File temp;
    final FileOutputStream out;
    final long length;
    long written;
    Transfer(File temp, FileOutputStream out, long length) {
      this.temp = temp; this.out = out; this.length = length;
    }
  }

  private final ServiceConnection serviceConnection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
      playback = ((PlaybackService.LocalBinder) binder).getService();
      serviceBound = true;
      playback.setListener(MainActivity.this::dispatchPlayerState);
      if (queuedPlaylist != null) playback.setQueue(queuedPlaylist);
      dispatchPlayerState(playback.getSnapshot());
    }
    @Override public void onServiceDisconnected(ComponentName name) {
      playback = null;
      serviceBound = false;
    }
  };

  private void dispatchPlayerState(String json) {
    if (web == null || !activityVisible) return;
    mainHandler.post(() -> {
      if (web != null && activityVisible) web.evaluateJavascript(
          "window.DonixNativeEvent && window.DonixNativeEvent(" + JSONObject.quote(json) + ")", null);
    });
  }

  private static String safeId(String id) {
    if (id == null || id.length() > 120 || !id.matches("[A-Za-z0-9_-]+")) return null;
    return id;
  }
  private File nativeAudioDirectory() {
    File folder = new File(getFilesDir(), "native_audio");
    if (!folder.exists()) folder.mkdirs();
    return folder;
  }
  private void sendCommand(String action, String trackId, boolean asForeground) {
    Intent i = new Intent(this, PlaybackService.class).setAction(action);
    if (trackId != null) i.putExtra(PlaybackService.EXTRA_TRACK_ID, trackId);
    try {
      if (asForeground) startForegroundService(i);
      else if (playback != null) playback.selectTrack(trackId, false);
      else startService(i);
    } catch (Exception e) { Log.e("DONIX", "Playback command failed", e); }
  }

  public class NativeBridge {
    @JavascriptInterface public boolean nativePlayback() { return true; }
    @JavascriptInterface public String getState() {
      return playback != null ? playback.getSnapshot() : "";
    }
    @JavascriptInterface public String getCover(String name) {
      String value = covers.get(name);
      return value == null ? "" : value;
    }

    /** The selected SAF URI is durable; no second copy is needed for newly added files. */
    @JavascriptInterface public void registerTrack(String id, String name, long size) {
      if (safeId(id) == null) return;
      synchronized (pendingImports) {
        int candidate = -1;
        for (int i = 0; i < pendingImports.size(); i++) {
          ImportedFile f = pendingImports.get(i);
          if (f.name.equals(name) && (size == f.size || size < 0 || f.size < 0)) {
            candidate = i; break;
          }
        }
        if (candidate == -1) for (int i = 0; i < pendingImports.size(); i++) {
          if (pendingImports.get(i).name.equals(name)) { candidate = i; break; }
        }
        if (candidate == -1) return;
        ImportedFile found = pendingImports.remove(candidate);
        getSharedPreferences(SOURCES, MODE_PRIVATE).edit()
            .putString("song:" + id, "uri:" + found.uri).apply();
      }
    }

    @JavascriptInterface public boolean hasSource(String id) {
      if (safeId(id) == null) return false;
      String ref = getSharedPreferences(SOURCES, MODE_PRIVATE).getString("song:" + id, null);
      if (ref == null) return false;
      if (ref.startsWith("uri:")) return true;
      if (ref.startsWith("file:")) return new File(nativeAudioDirectory(), ref.substring(5)).isFile();
      return false;
    }

    /** For tracks imported with an older app version: migrate IndexedDB's File in small chunks. */
    @JavascriptInterface public boolean beginTransfer(String id, long length) {
      if (safeId(id) == null || length <= 0 || length > 2_000_000_000L) return false;
      synchronized (transferLock) {
        if (transfers.containsKey(id)) return false;
        File folder = nativeAudioDirectory();
        if (folder.getUsableSpace() < length + 16L * 1024 * 1024) return false;
        File temp = new File(folder, id + ".partial");
        try {
          transfers.put(id, new Transfer(temp, new FileOutputStream(temp), length));
          return true;
        } catch (IOException e) { Log.w("DONIX", "Could not create migration file", e); return false; }
      }
    }
    @JavascriptInterface public boolean appendTransfer(String id, String chunk) {
      synchronized (transferLock) {
        Transfer t = transfers.get(id);
        if (t == null || chunk == null || chunk.length() > 320_000) return false;
        try {
          byte[] bytes = Base64.decode(chunk, Base64.DEFAULT);
          if (bytes.length == 0 || t.written + bytes.length > t.length) return false;
          t.out.write(bytes);
          t.written += bytes.length;
          return true;
        } catch (Exception e) { Log.w("DONIX", "Migration write failed", e); return false; }
      }
    }
    @JavascriptInterface public boolean finishTransfer(String id) {
      synchronized (transferLock) {
        Transfer t = transfers.remove(id);
        if (t == null) return false;
        try {
          t.out.close();
          if (t.written != t.length) { t.temp.delete(); return false; }
          File target = new File(nativeAudioDirectory(), id + ".audio");
          if (target.exists() && !target.delete()) { t.temp.delete(); return false; }
          if (!t.temp.renameTo(target)) { t.temp.delete(); return false; }
          getSharedPreferences(SOURCES, MODE_PRIVATE).edit()
              .putString("song:" + id, "file:" + target.getName()).apply();
          return true;
        } catch (Exception e) { t.temp.delete(); return false; }
      }
    }
    @JavascriptInterface public void abortTransfer(String id) {
      synchronized (transferLock) {
        Transfer t = transfers.remove(id);
        if (t == null) return;
        try { t.out.close(); } catch (Exception ignored) { }
        t.temp.delete();
      }
    }

    @JavascriptInterface public void setQueue(String json) {
      if (json == null || json.length() > 1_000_000) return;
      mainHandler.post(() -> {
        queuedPlaylist = json;
        if (playback != null) playback.setQueue(json);
      });
    }
    /** Start the foreground service immediately while the user is still in the app. */
    @JavascriptInterface public void beginPlayback() {
      mainHandler.post(() -> {
        try { startForegroundService(new Intent(MainActivity.this, PlaybackService.class)
            .setAction(PlaybackService.PREPARING)); }
        catch (Exception e) { Log.w("DONIX", "Cannot prepare foreground service", e); }
      });
    }
    @JavascriptInterface public void selectTrack(String id, boolean autoplay) {
      if (safeId(id) == null) return;
      mainHandler.post(() -> {
        // Already-running foreground service can load a song even if the UI just backgrounded.
        if (playback != null) playback.selectTrack(id, autoplay);
        else sendCommand(autoplay ? PlaybackService.START_TRACK :
            PlaybackService.SELECT_TRACK, id, autoplay);
      });
    }
    @JavascriptInterface public void play() {
      mainHandler.post(() -> sendCommand(PlaybackService.START_PLAY, null, true));
    }
    @JavascriptInterface public void pause() {
      mainHandler.post(() -> {
        if (playback != null) playback.pause();
        else try { startService(new Intent(MainActivity.this, PlaybackService.class)
            .setAction(PlaybackService.PAUSE)); } catch (Exception ignored) { }
      });
    }
    @JavascriptInterface public void seek(long millis) {
      mainHandler.post(() -> { if (playback != null) playback.seek((int) millis); });
    }
    @JavascriptInterface public void setVolume(double volume) {
      mainHandler.post(() -> { if (playback != null) playback.setVolume((float) volume); });
    }
    @JavascriptInterface public void setArtwork(String id, String artwork) {
      if (safeId(id) == null || artwork == null || artwork.length() > 3_000_000) return;
      mainHandler.post(() -> { if (playback != null) playback.setArtwork(id, artwork); });
    }
    @JavascriptInterface public void stop() {
      mainHandler.post(() -> { if (playback != null) playback.stopPlayback(); });
    }
    @JavascriptInterface public void clearLibrary() {
      synchronized (transferLock) {
        for (Transfer t : transfers.values()) {
          try { t.out.close(); } catch (Exception ignored) { }
          t.temp.delete();
        }
        transfers.clear();
      }
      File[] files = nativeAudioDirectory().listFiles();
      if (files != null) for (File file : files) file.delete();
      getSharedPreferences(SOURCES, MODE_PRIVATE).edit().clear().apply();
      mainHandler.post(() -> { if (playback != null) playback.stopPlayback(); });
    }
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1003);
    }
    web = new WebView(this);
    web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
    web.addJavascriptInterface(new NativeBridge(), "DonixNative");
    setContentView(web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true); // app-local asset page and saved HTML player
    s.setAllowContentAccess(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view,
          android.webkit.WebResourceRequest request) {
        if (!request.isForMainFrame()) return false;
        Uri uri = request.getUrl();
        String scheme = uri.getScheme(), host = uri.getHost();
        // Don't expose the native file/audio JS bridge to arbitrary remote pages.
        // Apple Music, Spotify and other external links open outside this WebView.
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
          try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
          catch (Exception e) { Toast.makeText(MainActivity.this, "リンクを開けませんでした",
              Toast.LENGTH_SHORT).show(); }
          return true;
        }
        return false;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
          FileChooserParams params) {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = callback;
        try {
          boolean multi = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
          Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          picker.addCategory(Intent.CATEGORY_OPENABLE);
          picker.setType("*/*");
          String[] types = params.getAcceptTypes();
          if (types != null) {
            ArrayList<String> mimes = new ArrayList<>();
            for (String type : types) {
              if (type != null && !type.isEmpty() && !type.startsWith(".") && type.contains("/"))
                mimes.add(type);
            }
            if (mimes.size() == 1) picker.setType(mimes.get(0));
            else if (mimes.size() > 1)
              picker.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
          }
          picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi);
          picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
              Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
          startActivityForResult(picker, PICK_FILES);
          return true;
        } catch (Exception e) {
          pendingFiles = null;
          callback.onReceiveValue(null);
          return false;
        }
      }
    });
    bindService(new Intent(this, PlaybackService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    web.loadUrl("file:///android_asset/index.html");
  }

  private void registerPickedFile(Uri uri) {
    if (uri == null) return;
    boolean persistable = false;
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      persistable = true;
    } catch (SecurityException e) {
      Log.w("DONIX", "URI provider does not allow persistable access; use app-local audio", e);
    }
    String name = null;
    long size = -1;
    try (Cursor cursor = getContentResolver().query(uri,
        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        name = cursor.getString(0);
        if (!cursor.isNull(1)) size = cursor.getLong(1);
      }
    } catch (Exception ignored) { }
    if (name == null) name = uri.getLastPathSegment();
    if (name != null && persistable) synchronized (pendingImports) {
      pendingImports.add(new ImportedFile(name, size, uri.toString()));
    }
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      r.setDataSource(this, uri);
      byte[] bytes = r.getEmbeddedPicture();
      if (bytes != null && bytes.length > 0 && bytes.length < 5 * 1024 * 1024) {
        String type = bytes.length > 3 && (bytes[0] & 255) == 255 ? "image/jpeg" : "image/png";
        covers.put(name, "data:" + type + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
      }
    } catch (Exception ignored) { }
    finally { try { r.release(); } catch (Exception ignored) { } }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK_FILES || pendingFiles == null) return;
    Uri[] uris = null;
    if (result == RESULT_OK && data != null) {
      LinkedHashSet<Uri> selected = new LinkedHashSet<>();
      ClipData clip = data.getClipData();
      if (clip != null) for (int i = 0; i < clip.getItemCount(); i++)
        selected.add(clip.getItemAt(i).getUri());
      else if (data.getData() != null) selected.add(data.getData());
      uris = selected.toArray(new Uri[0]);
    }
    synchronized (pendingImports) { pendingImports.clear(); }
    if (uris != null) for (Uri uri : uris) registerPickedFile(uri);
    pendingFiles.onReceiveValue(uris);
    pendingFiles = null;
  }
  @Override protected void onResume() {
    super.onResume();
    activityVisible = true;
    if (web != null) web.evaluateJavascript(
        "window.DonixPullState && window.DonixPullState()", null);
  }
  @Override protected void onPause() {
    activityVisible = false;
    super.onPause();
  }
  @Override public void onBackPressed() {
    if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
  }
  @Override protected void onDestroy() {
    if (playback != null) playback.setListener(null);
    if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
    if (pendingFiles != null) { pendingFiles.onReceiveValue(null); pendingFiles = null; }
    if (web != null) { web.destroy(); web = null; }
    super.onDestroy();
  }
}
