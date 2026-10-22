import com.gather.quotapocket.AlertRules;
import com.gather.quotapocket.UsagePlan;
import java.time.ZoneId;

public final class AlertRulesTest {
    private static int count;
    private static final long D = UsagePlan.FIVE_HOURS, START = 1_790_000_000_000L, RESET = START + D, MID = START + D / 2;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kuala_Lumpur");
    private static void check(boolean condition, String message) {
        count++; if (!condition) throw new AssertionError(message);
    }
    private static AlertRules.Result at(UsagePlan p, AlertRules.State s, long sample, double delta) {
        double expected = p.expected(RESET, D, sample, ZONE);
        return AlertRules.evaluate(p, s, "a", RESET, D, sample, sample, 100 - expected - delta, ZONE);
    }
    public static void main(String[] args) {
        UsagePlan p = new UsagePlan(false);
        AlertRules.State empty = new AlertRules.State();
        AlertRules.Result baseline = at(p, empty, MID, 0);
        check(baseline != null && !baseline.reset && baseline.direction == 0, "first snapshot is not a reset");
        AlertRules.Result r = at(p, baseline.state, MID + 1000, 9.99);
        check(r.direction == 0, "just below threshold");
        r = at(p, r.state, MID + 2000, 10);
        check(r.direction == 1 && r.state.overLevel == 1, "exact threshold alerts");
        r = at(p, r.state, MID + 3000, 15);
        check(r.direction == 0, "same severity does not alert");
        r = at(p, r.state, MID + 4000, 1);
        check(r.direction == 0 && r.state.overLevel == 1, "recovery retains high-water mark");
        r = at(p, r.state, MID + 5000, 12);
        check(r.direction == 0, "crossing old threshold does not repeat");
        r = at(p, r.state, MID + 6000, 20);
        check(r.direction == 1 && r.state.overLevel == 2, "worsening triggers next level");
        r = at(p, r.state, MID + 7000, 41);
        check(r.direction == 1 && r.state.overLevel == 4, "jump skips intermediate messages");
        AlertRules.State restarted = AlertRules.State.parse(r.state.json());
        r = at(p, restarted, MID + 8000, 41);
        check(r.direction == 0 && r.state.overLevel == 4, "app restart cannot repeat");
        r = at(p, r.state, MID + 9000, -10);
        check(r.direction == -1 && r.state.underLevel == 1 && r.state.overLevel == 4, "underuse is independent");
        r = at(p, r.state, MID + 10000, -20);
        check(r.direction == -1 && r.state.underLevel == 2, "underuse worsening");
        r = at(p, r.state, MID + 11000, -1);
        r = at(p, r.state, MID + 12000, -15);
        check(r.direction == 0, "underuse recovery cannot repeat old level");
        check(at(p, r.state, MID + 12000, -40) == null, "same sample cannot be reinterpreted");
        check(at(p, r.state, MID, -40) == null, "out-of-order data ignored");
        check(AlertRules.evaluate(p, r.state, "a", RESET, D, MID + 13000, MID + 13000 + AlertRules.MAX_AGE + 1, 0, ZONE) == null, "stale snapshot rejected");
        check(AlertRules.evaluate(p, empty, "a", RESET, D, MID, MID - 1, 50, ZONE) == null, "future snapshot rejected");
        check(AlertRules.evaluate(p, empty, "a", 0, D, MID, MID, 50, ZONE) == null, "missing reset rejected");
        check(AlertRules.evaluate(p, empty, "a", MID, D, MID, MID, 50, ZONE) == null, "expired window rejected");
        check(AlertRules.evaluate(p, empty, "a", RESET, D, MID, RESET, 50, ZONE) == null, "clock passing reset is not a reset event");
        check(AlertRules.evaluate(p, empty, "a", MID + D + 1, D, MID, MID, 50, ZONE) == null, "impossible duration rejected");
        for (double remaining : new double[]{-1, 101, Double.NaN, Double.POSITIVE_INFINITY})
            check(AlertRules.evaluate(p, empty, "a", RESET, D, MID, MID, remaining, ZONE) == null, "invalid percentage rejected");
        long nextSample = RESET + D / 2;
        AlertRules.Result next = AlertRules.evaluate(p, r.state, "a", RESET + D, D, nextSample, nextSample, 30, ZONE);
        check(next.reset && next.direction == 1 && next.state.overLevel == 2 && next.state.underLevel == 0, "new cycle resets severity and reports reset");
        check(AlertRules.evaluate(p, next.state, "a", RESET + D, D, nextSample, nextSample, 30, ZONE) == null, "reset cannot repeat");
        AlertRules.Result nextAgain = AlertRules.evaluate(p, next.state, "a", RESET + D, D, nextSample + 1000, nextSample + 1000, 30, ZONE);
        check(!nextAgain.reset && nextAgain.direction == 0, "later sample in same new cycle is quiet");
        AlertRules.Result changed = AlertRules.evaluate(p, r.state, "b", RESET + D, D, nextSample, nextSample, 50, ZONE);
        check(!changed.reset && changed.state.overLevel == 0, "new source is not a reset");
        AlertRules.Result correction = AlertRules.evaluate(p, r.state, "a", RESET + 120_000, D, MID + 60_000, MID + 60_000, 20, ZONE);
        check(!correction.reset && correction.state.overLevel == 4, "future schedule adjustment retains history");
        AlertRules.Result drift = AlertRules.evaluate(p, r.state, "a", RESET - 1000, D, MID + 60_000, MID + 60_000, 20, ZONE);
        check(!drift.reset && drift.state.overLevel == 4, "small timestamp drift retains history");
        check(AlertRules.evaluate(p, r.state, "a", RESET - 120_000, D, MID + 60_000, MID + 60_000, 20, ZONE) == null, "backward cycle cannot erase history");
        p.overEnabled = false;
        check(at(p, empty, MID, 30).direction == 0, "overuse toggle respected");
        check(at(p, empty, MID, -30).direction == -1, "underuse independent toggle");
        p.underEnabled = false;
        check(at(p, empty, MID, -30).direction == 0, "underuse toggle respected");
        p.enabled = false;
        check(at(p, empty, MID, 0) == null, "window switch respected");
        p = new UsagePlan(false); p.over = 15; p.step = 5;
        check(at(p, empty, MID, 14).direction == 0, "custom threshold below");
        check(at(p, empty, MID, 15).state.overLevel == 1, "custom threshold");
        check(at(p, empty, MID, 20).state.overLevel == 2, "custom escalation step");
        check(AlertRules.fresh(MID, MID + AlertRules.MAX_AGE), "age boundary");
        check(!AlertRules.fresh(0, MID), "empty timestamp");
        check(AlertRules.State.parse("bad").reset == 0, "corrupt history initializes safely");
        System.out.println(count + " alert-rule assertions passed.");
    }
}
