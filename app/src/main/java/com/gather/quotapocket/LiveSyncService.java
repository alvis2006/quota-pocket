package com.gather.quotapocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LiveSyncService extends Service {
    private static final String CHANNEL = "quota_sync", STOP = "com.gather.quotapocket.STOP_LIVE";
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile QuotaSync active;
    private long started;
    private boolean running;
    private static volatile boolean activeService;
    static boolean active(Context c) { return activeService; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !new QuotaStore(this).refreshable()) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "額度自動同步", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 4, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 5, new Intent(this, LiveSyncService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("額度口袋 · 每分鐘更新").setContentText("直接從手機更新額度；點此查看狀態")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止每分鐘更新", stop).build()).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(10, n);
        } catch (RuntimeException e) {
            new QuotaStore(this).prefs.edit().putString("live_note", "Android 暫時不允許持續同步，可使用一般更新或稍後重試").apply();
            stopSelf(); return START_NOT_STICKY;
        }
        started = SystemClock.elapsedRealtime(); running = true; activeService = true;
        timer.scheduleWithFixedDelay(() -> {
            if (!new QuotaStore(this).refreshable() || SystemClock.elapsedRealtime() - started >= 355 * 60_000L) {
                new QuotaStore(this).prefs.edit().putString("live_note", "每分鐘更新已結束，可重新開啟").apply(); stopSelf(); return;
            }
            new QuotaStore(this).prefs.edit().putLong("live_heartbeat", System.currentTimeMillis()).remove("live_note").apply();
            QuotaSync sync = new QuotaSync(this); active = sync; sync.run(); active = null;
        }, 0, 60, TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int type) {
        new QuotaStore(this).prefs.edit().putString("live_note", "Android 已暫停每分鐘更新，請開啟 App 重新啟動").apply();
        stopSelf();
    }
    @Override public void onDestroy() {
        activeService = false;
        timer.shutdownNow(); QuotaSync sync = active; if (sync != null) sync.cancel();
        new QuotaStore(this).prefs.edit().remove("live_heartbeat").apply();
        stopForeground(STOP_FOREGROUND_REMOVE); RefreshJob.schedule(this); QuotaWidget.renderAll(this); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
