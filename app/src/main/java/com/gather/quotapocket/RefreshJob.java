package com.gather.quotapocket;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RefreshJob extends JobService {
    private static final int MANUAL = 100, PERIODIC = 101;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, QuotaSync> jobs = new ConcurrentHashMap<>();

    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (!new QuotaStore(c).refreshable()) { scheduler.cancel(PERIODIC); return; }
        if (scheduler.getPendingJob(PERIODIC) != null) return;
        scheduler.schedule(new JobInfo.Builder(PERIODIC, new ComponentName(c, RefreshJob.class))
            .setPeriodic(15 * 60_000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    static void request(Context c) {
        QuotaStore store = new QuotaStore(c);
        if (!store.refreshable() || store.loading()) return;
        schedule(c);
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(MANUAL) != null) return;
        int result = scheduler.schedule(new JobInfo.Builder(MANUAL, new ComponentName(c, RefreshJob.class)).setOverrideDeadline(0).build());
        if (result == JobScheduler.RESULT_FAILURE) store.finish(store.revision(), null, "無法安排更新，請稍後重試");
        QuotaWidget.renderAll(c);
    }
    static void cancel(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class); scheduler.cancel(MANUAL); scheduler.cancel(PERIODIC);
        c.stopService(new android.content.Intent(c, LiveSyncService.class));
    }
    @Override public boolean onStartJob(JobParameters params) {
        if (!new QuotaStore(this).refreshable()) return false;
        QuotaSync sync = new QuotaSync(this); jobs.put(params.getJobId(), sync);
        executor.submit(() -> { sync.run(); main.post(() -> { if (jobs.remove(params.getJobId(), sync)) jobFinished(params, false); }); });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { QuotaSync sync = jobs.remove(params.getJobId()); if (sync != null) sync.cancel(); return false; }
    @Override public void onDestroy() { for (QuotaSync sync : jobs.values()) sync.cancel(); jobs.clear(); executor.shutdownNow(); super.onDestroy(); }
    static String readLimited(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096]; int n;
        long deadline = System.nanoTime() + 20_000_000_000L;
        while ((n = in.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new java.io.IOException("Read cancelled");
            if (out.size() + n > QuotaData.MAX_BYTES) throw new IllegalArgumentException("資料過大");
            out.write(chunk, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
