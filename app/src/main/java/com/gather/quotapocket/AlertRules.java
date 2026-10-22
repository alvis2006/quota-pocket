package com.gather.quotapocket;

import java.time.ZoneId;
import org.json.JSONObject;

/** Persistent high-water marks: recovering and crossing the same level cannot spam the user. */
public final class AlertRules {
    public static final long MAX_AGE = UsagePlan.HOUR;
    public static final class State {
        public long reset, sample;
        public int overLevel, underLevel;
        public String source = "";
        public String json() {
            try { return new JSONObject().put("reset", reset).put("sample", sample).put("over", overLevel)
                .put("under", underLevel).put("source", source).toString(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        public static State parse(String raw) {
            State s = new State();
            try {
                JSONObject o = new JSONObject(raw);
                s.reset = o.getLong("reset"); s.sample = o.getLong("sample");
                s.overLevel = o.getInt("over"); s.underLevel = o.getInt("under"); s.source = o.getString("source");
            } catch (Exception ignored) {}
            return s;
        }
    }
    public static final class Result {
        public final State state;
        public final boolean reset;
        public final int direction;
        public final double expected, used;
        Result(State s, boolean r, int d, double expected, double used) {
            state = s; reset = r; direction = d; this.expected = expected; this.used = used;
        }
    }
    public static boolean fresh(long sample, long now) { return sample > 0 && sample <= now && now - sample <= MAX_AGE; }
    public static Result evaluate(UsagePlan p, State previous, String source, long reset, long duration,
                                  long sample, long now, double remaining, ZoneId zone) {
        if (!p.enabled || !fresh(sample, now) || reset <= sample || reset <= now ||
            reset - sample > duration || !Double.isFinite(remaining) || remaining < 0 || remaining > 100) return null;
        boolean sameSource = source.equals(previous.source);
        if (sameSource && sample <= previous.sample) return null;
        if (sameSource && previous.reset > 0 && reset < previous.reset - 60_000) return null;
        boolean sameCycle = sameSource && previous.reset > 0 && Math.abs(reset - previous.reset) <= 60_000;
        // Advancing a future reset is a schedule correction, not evidence of a reset.
        boolean didReset = sameSource && previous.reset > 0 && !sameCycle &&
            reset > previous.reset && sample >= previous.reset;
        State s = new State(); s.source = source; s.reset = reset; s.sample = sample;
        // Keep high-water marks for schedule corrections too.
        if (sameSource && !didReset) { s.overLevel = previous.overLevel; s.underLevel = previous.underLevel; }
        double expected = p.expected(reset, duration, sample, zone), used = 100 - remaining, delta = used - expected;
        int over = p.overEnabled ? level(delta, p.over, p.step) : 0;
        int under = p.underEnabled ? level(-delta, p.under, p.step) : 0;
        int direction = over > s.overLevel ? 1 : under > s.underLevel ? -1 : 0;
        s.overLevel = Math.max(s.overLevel, over); s.underLevel = Math.max(s.underLevel, under);
        return new Result(s, didReset, direction, expected, used);
    }
    private static int level(double delta, double threshold, double step) {
        return delta + 1e-8 < threshold ? 0 : 1 + (int) Math.floor((delta - threshold + 1e-8) / step);
    }
}
