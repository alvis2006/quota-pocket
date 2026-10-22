package com.gather.quotapocket;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class QuotaWidget extends AppWidgetProvider {
    static final String REFRESH = "com.gather.quotapocket.REFRESH";
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        renderAll(c);
        if (new QuotaStore(c).refreshable()) RefreshJob.request(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, Bundle options) { renderAll(c); }
    @Override public void onDisabled(Context c) { RefreshJob.cancel(c); }
    @Override public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (REFRESH.equals(intent.getAction())) {
            if (new QuotaStore(c).refreshable()) RefreshJob.request(c);
            else renderAll(c);
        }
    }
    static void renderAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        int[] ids = manager.getAppWidgetIds(new ComponentName(c, QuotaWidget.class));
        for (int id : ids) {
            Bundle o = manager.getAppWidgetOptions(id);
            // Portrait size: width is the minimum, height the maximum. 0 means the launcher did not say.
            manager.updateAppWidget(id, views(c, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)));
        }
    }

    /** Layout metrics for one widget size, in dp. */
    static final class Size {
        final boolean wide;
        final int ring;
        Size(int widthDp, int heightDp) {
            int column = widthDp <= 0 ? 140 : (widthDp - 49) / 2;
            wide = column >= 176;
            int byWidth = wide ? Math.min(84, column - 96) : column >= 136 ? 60 : 52;
            int byHeight = heightDp <= 0 ? byWidth : heightDp - 76;
            ring = Math.max(44, Math.min(byWidth, byHeight));
        }
    }

    static RemoteViews views(Context c, int widthDp, int heightDp) {
        QuotaStore store = new QuotaStore(c);
        QuotaData data = store.data();
        Size size = new Size(widthDp, heightDp);
        long now = System.currentTimeMillis();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_quota);
        provider(c, rv, data == null ? null : data.claude, true, size, now);
        provider(c, rv, data == null ? null : data.codex, false, size, now);

        String status;
        boolean error = !store.error().isEmpty();
        if (store.loading()) status = "正在取得最新額度…";
        else if (error) status = store.error() + (data == null ? "" : " · 保留上次資料");
        else if (data == null) status = "開啟 App 連接資料";
        else if (data.demo) status = "示範資料 · 非實際額度";
        else {
            String mode = store.mode();
            String source = mode.equals("codex") ? "手機直連" : mode.equals("remote") ? "連線來源" : mode.equals("local") || !store.claudeLinked() ? "本機匯入" : "Claude 檔案";
            boolean stale = mode.equals("codex") ? now - data.generatedAt > 180_000 : QuotaMath.stale(data.generatedAt, now);
            status = source + " · " + clock(data.generatedAt, now) + (stale ? " · 待更新" : " 更新") + (LiveSyncService.active(c) ? " · 每分鐘" : "");
            QuotaData claude = store.claudeSnapshot();
            if (claude != null && QuotaMath.stale(claude.generatedAt, now)) status = "Claude 資料較舊 · " + status;
        }
        String claudeError = store.prefs.getString("claude_error", "");
        if (!claudeError.isEmpty() && !store.loading()) { status = claudeError + " · " + status; error = true; }
        rv.setTextViewText(R.id.widget_status, status);
        rv.setTextColor(R.id.widget_status, c.getColor(error ? R.color.error : R.color.muted_text));
        rv.setContentDescription(R.id.widget_refresh, store.loading() ? "更新中" : store.refreshable() ? "更新剩餘額度" : "開啟設定");
        rv.setInt(R.id.widget_refresh, "setImageAlpha", store.loading() ? 100 : 255);

        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent refresh = PendingIntent.getBroadcast(c, 1, new Intent(c, QuotaWidget.class).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.widget_root, open);
        rv.setOnClickPendingIntent(R.id.widget_refresh, store.refreshable() ? refresh : open);
        return rv;
    }

    private static String clock(long time, long now) {
        boolean today = time / 86_400_000 == now / 86_400_000 && now - time < 86_400_000;
        return new SimpleDateFormat(today ? "HH:mm" : "MM/dd HH:mm", Locale.TRADITIONAL_CHINESE).format(new Date(time));
    }

    private static void provider(Context c, RemoteViews rv, QuotaData.Provider p, boolean claude, Size size, long now) {
        String name = claude ? "Claude" : "Codex";
        int shortColor = c.getColor(claude ? R.color.claude_short : R.color.codex_short);
        int weeklyColor = c.getColor(claude ? R.color.claude_weekly : R.color.codex_weekly);
        int[][] ids = claude ? new int[][] {
            {R.id.c_row_1, R.id.c_dot_1, R.id.c_label_1, R.id.c_value_1, R.id.c_reset_1},
            {R.id.c_row_2, R.id.c_dot_2, R.id.c_label_2, R.id.c_value_2, R.id.c_reset_2}
        } : new int[][] {
            {R.id.x_row_1, R.id.x_dot_1, R.id.x_label_1, R.id.x_value_1, R.id.x_reset_1},
            {R.id.x_row_2, R.id.x_dot_2, R.id.x_label_2, R.id.x_value_2, R.id.x_reset_2}
        };
        rv.setTextViewText(claude ? R.id.c_plan : R.id.x_plan, p == null ? "未連接" : p.plan);

        // Outer ring = shorter window, inner ring = weekly. Colour follows the window type, not its slot.
        List<QuotaData.Window> ordered = new ArrayList<>();
        if (p != null) {
            for (QuotaData.Window w : p.windows) if (!QuotaMath.weekly(w.label)) ordered.add(w);
            for (QuotaData.Window w : p.windows) if (QuotaMath.weekly(w.label)) ordered.add(w);
        }
        int[] colors = new int[2];
        for (int i = 0; i < 2; i++) {
            QuotaData.Window w = ordered.size() > i ? ordered.get(i) : null;
            colors[i] = w != null && QuotaMath.weekly(w.label) ? weeklyColor : w != null ? shortColor : i == 0 ? shortColor : weeklyColor;
            rv.setViewVisibility(ids[i][0], w == null && (p != null || i == 1) ? View.GONE : View.VISIBLE);
            rv.setTextColor(ids[i][1], colors[i]);
            rv.setTextViewText(ids[i][2], w == null ? "尚無資料" : w.label);
            rv.setTextViewText(ids[i][3], w == null ? "—" : percent(w.remaining));
            rv.setTextViewText(ids[i][4], w == null ? "開啟 App 設定" : QuotaMath.ringReset(w.resetsAt, now, size.wide));
            rv.setContentDescription(ids[i][0], name + " " + (w == null ? "未連接" : w.label + "剩餘 " + format(w.remaining) + "%，" + QuotaMath.resetText(w.resetsAt, now)));
        }

        double outer = ordered.isEmpty() ? Double.NaN : ordered.get(0).remaining;
        double inner = ordered.size() > 1 ? ordered.get(1).remaining : Double.NaN;
        String center = null;
        int centerColor = colors[0];
        if (!ordered.isEmpty()) {
            int tight = ordered.size() > 1 && inner < outer ? 1 : 0;
            center = format(ordered.get(tight).remaining);
            centerColor = colors[tight];
        }
        int ring = claude ? R.id.c_ring : R.id.x_ring;
        rv.setImageViewBitmap(ring, RingRenderer.render(c, size.ring, outer, inner, colors[0], colors[1], center, centerColor));
        rv.setContentDescription(ring, name + (center == null ? " 未連接" : " 最低剩餘 " + center + "%"));
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, v == Math.floor(v) ? "%.0f" : "%.1f", v);
    }
    private static CharSequence percent(double v) {
        SpannableString s = new SpannableString(format(v) + "%");
        s.setSpan(new RelativeSizeSpan(0.68f), s.length() - 1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
