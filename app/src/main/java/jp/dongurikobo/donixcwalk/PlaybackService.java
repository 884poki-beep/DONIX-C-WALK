package jp.dongurikobo.donixcwalk;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;

/** Owns actual audio playback independent of the WebView renderer. */
public final class PlaybackService extends Service {
    static final String CHANNEL="donix_playback";
    static final String PLAY="native.play", PAUSE="native.pause", SEEK="native.seek", VOLUME="native.volume", STOP="native.stop", WEB_PLAY="web.play", WEB_PAUSE="web.pause";
    static volatile Notification currentNotification;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private String currentFile="";
    private boolean wantedPlay=false;
    private int volume=80;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(player!=null){try{if(player.isPlaying()&&player.getDuration()>0&&player.getCurrentPosition()>=player.getDuration()-350){
            // The next track is requested from the UI when it is available.
        }}catch(Exception ignored){}}
        handler.postDelayed(this,1500);
    }};
    @Override public void onCreate(){super.onCreate();
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"DONIX C-WALK 再生",NotificationManager.IMPORTANCE_LOW));
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DONIX:CWalkAudio");wakeLock.setReferenceCounted(false);}
        handler.post(ticker);
    }
    private Notification notification(){Notification n=currentNotification;if(n!=null)return n;
        Notification.Builder b=new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DONIX C-WALK").setContentText("音楽を再生中").setOngoing(true);
        if(Build.VERSION.SDK_INT>=26)b.setChannelId(CHANNEL);return b.build();}
    private void prepare(String path, int pos, boolean autoplay){
        if(path==null||path.isEmpty())return;
        if(path.equals(currentFile)&&player!=null){seek(pos);if(autoplay)resume();return;}
        releasePlayer();currentFile=path;
        try{
            player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(path);player.setOnPreparedListener(mp->{mp.setVolume(volume/100f,volume/100f);if(pos>0)mp.seekTo(pos);if(autoplay)resume();});
            player.setOnCompletionListener(mp->{wantedPlay=false;releaseWake();sendBroadcast(new Intent("jp.dongurikobo.donixcwalk.TRACK_ENDED").setPackage(getPackageName()));});
            player.prepareAsync();
        }catch(Exception ex){android.util.Log.e("DONIX","Native playback failed",ex);releasePlayer();}
    }
    private void seek(int pos){try{if(player!=null)player.seekTo(Math.max(0,pos));}catch(Exception ignored){}}
    private void resume(){wantedPlay=true;try{if(player!=null){player.start();if(wakeLock!=null&&!wakeLock.isHeld())wakeLock.acquire();}}catch(Exception ex){android.util.Log.w("DONIX","start",ex);}}
    private void pause(){wantedPlay=false;try{if(player!=null&&player.isPlaying())player.pause();}catch(Exception ignored){}releaseWake();}
    private void releaseWake(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}
    private void releasePlayer(){if(player!=null){try{player.reset();player.release();}catch(Exception ignored){}player=null;}}
    @Override public int onStartCommand(Intent i,int flags,int startId){
        startForeground(MainActivity.MEDIA_NOTIFICATION_ID,notification());
        if(i!=null){String action=i.getAction();if(PLAY.equals(action))prepare(i.getStringExtra("file"),i.getIntExtra("position",0),true);
            else if(PAUSE.equals(action))pause();
            else if(WEB_PLAY.equals(action)){if(wakeLock!=null&&!wakeLock.isHeld())wakeLock.acquire();}
            else if(WEB_PAUSE.equals(action)){releaseWake();}
            else if(SEEK.equals(action))seek(i.getIntExtra("position",0));
            else if(VOLUME.equals(action)){volume=i.getIntExtra("volume",80);if(player!=null)player.setVolume(volume/100f,volume/100f);}
            else if(STOP.equals(action)){pause();releasePlayer();currentFile="";stopSelf();}
        }
        return START_STICKY;
    }
    @Override public void onDestroy(){handler.removeCallbacks(ticker);pause();releasePlayer();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
