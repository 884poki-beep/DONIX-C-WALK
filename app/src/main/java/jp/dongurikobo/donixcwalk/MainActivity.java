package jp.dongurikobo.donixcwalk;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.content.ClipData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 102;
    private WebView webView;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private boolean isPlaying = false;
    private Bitmap albumArtwork;
    private String songTitle = "DONIX C-WALK", songArtist = "dongurikobo";
    static final int MEDIA_NOTIFICATION_ID = 201;
    private boolean foregroundStarted = false;
    private static final String ACTION_PLAY = "jp.dongurikobo.donixcwalk.PLAY";
    private static final String ACTION_PAUSE = "jp.dongurikobo.donixcwalk.PAUSE";
    private static final String ACTION_PREV = "jp.dongurikobo.donixcwalk.PREV";
    private static final String ACTION_NEXT = "jp.dongurikobo.donixcwalk.NEXT";
    private final class MediaBridge {
        @JavascriptInterface public void artwork(String dataUrl) {
            Bitmap bitmap = null;
            try {
                if (dataUrl != null && dataUrl.startsWith("data:image/") && dataUrl.length() < 500000) {
                    String encoded = dataUrl.substring(dataUrl.indexOf(',') + 1);
                    byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Exception ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> { albumArtwork = result; updateSession(); });
        }

        @JavascriptInterface public void update(String title, String artist, boolean playing) {
            runOnUiThread(() -> {
                songTitle = title == null || title.isEmpty() ? "DONIX C-WALK" : title;
                songArtist = artist == null || artist.isEmpty() ? "dongurikobo" : artist;
                isPlaying = playing;
                updateSession();
            });
        }
    }
    private void runPlayerCommand(String command) {
        if (webView == null) return;
        webView.evaluateJavascript("window.donixNativeCommand && window.donixNativeCommand('" + command + "')", null);
    }
    private void updateSession() {
        if (mediaSession == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, songTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, songArtist);
        if (albumArtwork != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArtwork);
        mediaSession.setMetadata(metadata.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SKIP_TO_NEXT;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, isPlaying ? 1f : 0f).build());
        if (isPlaying) {
            // Foreground playback must not depend on notification permission.
            // Android can run a foreground service even when notification permission is denied.
            startPlaybackService();
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                showNotification();
            }
        }
        else {
            stopPlaybackService();
            if (notificationManager != null) notificationManager.cancel(MEDIA_NOTIFICATION_ID);
        }
    }
    private PendingIntent mediaAction(String action, int requestCode) {
        Intent intent = new Intent(this, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void startPlaybackService() {
        if (foregroundStarted) return;
        // A foreground media-playback service keeps this app process prioritized while audio plays.
        try {
            Intent intent = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            foregroundStarted = true;
        } catch (Exception e) { android.util.Log.w("DONIX", "Unable to start playback service", e); }
    }
    private void stopPlaybackService() {
        if (!foregroundStarted) return;
        stopService(new Intent(this, PlaybackService.class));
        foregroundStarted = false;
    }
    private void showNotification() {
        if (notificationManager == null || mediaSession == null) return;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(songTitle).setContentText(songArtist)
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_previous, "Previous", mediaAction(ACTION_PREV, 1))
                .addAction(android.R.drawable.ic_media_pause, "Pause", mediaAction(ACTION_PAUSE, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", mediaAction(ACTION_NEXT, 3))
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (albumArtwork != null) builder.setLargeIcon(albumArtwork);
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId("donix_playback");
        Notification notification = builder.build();
        PlaybackService.currentNotification = notification;
        notificationManager.notify(MEDIA_NOTIFICATION_ID, notification);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED && isPlaying) {
            showNotification();
            startPlaybackService();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) handleMediaAction(intent.getAction());
    }
    private void handleMediaAction(String action) {
        if (ACTION_PLAY.equals(action)) runPlayerCommand("play");
        else if (ACTION_PAUSE.equals(action)) runPlayerCommand("pause");
        else if (ACTION_PREV.equals(action)) runPlayerCommand("previous");
        else if (ACTION_NEXT.equals(action)) runPlayerCommand("next");
    }

    private ValueCallback<Uri[]> pendingFiles;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "donix_playback", "DONIX C-WALK 再生", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
        mediaSession = new MediaSession(this, "DONIX-C-WALK");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { runPlayerCommand("play"); }
            @Override public void onPause() { runPlayerCommand("pause"); }
            @Override public void onSkipToNext() { runPlayerCommand("next"); }
            @Override public void onSkipToPrevious() { runPlayerCommand("previous"); }
        });
        mediaSession.setActive(true);
        handleMediaAction(getIntent() == null ? null : getIntent().getAction());
        webView = new WebView(this);
        webView.addJavascriptInterface(new MediaBridge(), "DonixAndroid");
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    // ACTION_OPEN_DOCUMENT supports selecting multiple MP3s in Android's file picker.
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    String[] types = params.getAcceptTypes();
                    if (types != null && types.length > 0) {
                        ArrayList<String> mime = new ArrayList<>();
                        for (String type : types) {
                            if (type == null || type.trim().isEmpty()) continue;
                            if (type.startsWith(".")) continue; // File extensions aren't MIME types.
                            mime.add(type);
                        }
                        if (mime.size() == 1) picker.setType(mime.get(0));
                        else if (mime.size() > 1) picker.putExtra(Intent.EXTRA_MIME_TYPES, mime.toArray(new String[0]));
                    }
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(picker, FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) || "about".equals(uri.getScheme())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_REQUEST && pendingFiles != null) {
            Uri[] selected = null;
            if (result == RESULT_OK && data != null) {
                LinkedHashSet<Uri> uris = new LinkedHashSet<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) uris.add(uri);
                    }
                }
                if (data.getData() != null) uris.add(data.getData());
                if (!uris.isEmpty()) selected = uris.toArray(new Uri[0]);
            }
            pendingFiles.onReceiveValue(selected);
            pendingFiles = null;
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (pendingFiles != null) { pendingFiles.onReceiveValue(null); pendingFiles = null; }
        stopPlaybackService();
        if (notificationManager != null) notificationManager.cancel(MEDIA_NOTIFICATION_ID);
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); mediaSession = null; }
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
