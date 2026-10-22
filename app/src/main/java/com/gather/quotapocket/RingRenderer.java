package com.gather.quotapocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Draws the concentric quota rings as a bitmap, since RemoteViews cannot host custom views. */
final class RingRenderer {
    private RingRenderer() {}

    /** Percentages are remaining 0–100; NaN hides that ring. A null centre draws a dash. */
    static Bitmap render(Context c, int sizeDp, double outer, double inner, int outerColor, int innerColor, String center, int centerColor) {
        float density = c.getResources().getDisplayMetrics().density;
        int px = Math.max(1, Math.round(sizeDp * density));
        Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float stroke = px * 0.09f, gap = px * 0.04f, mid = px / 2f;
        float outerRadius = mid - stroke / 2f, innerRadius = outerRadius - stroke - gap;
        int track = c.getColor(R.color.ring_track);
        ring(canvas, mid, outerRadius, stroke, outer, outerColor, track, true);
        ring(canvas, mid, innerRadius, stroke, inner, innerColor, track, Double.isNaN(outer));

        Paint number = text(px * 0.22f, center == null ? c.getColor(R.color.muted_text) : centerColor);
        Paint unit = text(px * 0.12f, number.getColor());
        String value = center == null ? "—" : center, suffix = center == null ? "" : "%";
        float space = suffix.isEmpty() ? 0 : px * 0.01f;
        float width = number.measureText(value) + space + unit.measureText(suffix);
        Paint.FontMetrics m = number.getFontMetrics();
        float baseline = mid - (m.ascent + m.descent) / 2f, x = mid - width / 2f;
        canvas.drawText(value, x, baseline, number);
        canvas.drawText(suffix, x + number.measureText(value) + space, baseline, unit);
        return bitmap;
    }

    private static void ring(Canvas canvas, float mid, float radius, float stroke, double percent, int color, int track, boolean drawTrack) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        if (drawTrack || !Double.isNaN(percent)) {
            paint.setColor(track);
            canvas.drawCircle(mid, mid, radius, paint);
        }
        if (Double.isNaN(percent) || percent <= 0) return;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        RectF oval = new RectF(mid - radius, mid - radius, mid + radius, mid + radius);
        if (percent >= 100) canvas.drawCircle(mid, mid, radius, paint);
        else canvas.drawArc(oval, -90, (float) (360 * percent / 100), false, paint);
    }

    private static Paint text(float size, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return paint;
    }
}
