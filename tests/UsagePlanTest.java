import com.gather.quotapocket.UsagePlan;
import java.time.Instant;
import java.time.ZoneId;

public final class UsagePlanTest {
    private static int count;
    private static final ZoneId MY = ZoneId.of("Asia/Kuala_Lumpur");
    private static void check(boolean condition, String message) {
        count++; if (!condition) throw new AssertionError(message);
    }
    private static void near(double expected, double actual) {
        check(Math.abs(expected - actual) < 0.000001, expected + " != " + actual);
    }
    private static long time(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private static void invalid(UsagePlan p) {
        try { p.validate(UsagePlan.WEEK); throw new AssertionError("accepted invalid plan"); }
        catch (IllegalArgumentException expected) { count++; }
    }
    public static void main(String[] args) {
        long start = time("2026-10-04T16:00:00Z"), reset = start + UsagePlan.WEEK; // Monday 00:00 MY
        UsagePlan p = new UsagePlan(true);
        near(0, p.expected(reset, UsagePlan.WEEK, start, MY));
        near(50, p.expected(reset, UsagePlan.WEEK, start + UsagePlan.WEEK / 2, MY));
        near(100, p.expected(reset, UsagePlan.WEEK, reset, MY));
        near(0, p.expected(reset, UsagePlan.WEEK, start - 1, MY));
        near(100, p.expected(reset, UsagePlan.WEEK, reset + 1, MY));
        UsagePlan shortPlan = new UsagePlan(false);
        near(50, shortPlan.expected(start + UsagePlan.FIVE_HOURS, UsagePlan.FIVE_HOURS, start + UsagePlan.FIVE_HOURS / 2, MY));
        p.mode = "weighted"; p.weights = new double[]{2, 1, 1, 1, 1, 0, 0};
        near(100.0 / 3, p.expected(reset, UsagePlan.WEEK, start + 24 * UsagePlan.HOUR, MY));
        near(100, p.expected(reset, UsagePlan.WEEK, start + 5 * 24 * UsagePlan.HOUR, MY));
        near(100, p.expected(reset, UsagePlan.WEEK, reset, MY));
        long noon = start + 12 * UsagePlan.HOUR;
        // Midday week boundaries split Monday into two partial segments.
        near(100.0 / 6, p.expected(noon + UsagePlan.WEEK, UsagePlan.WEEK, noon + 12 * UsagePlan.HOUR, MY));
        p.weights = new double[]{1, 1, 1, 1, 1, 1, 1};
        near(50, p.expected(reset, UsagePlan.WEEK, start + UsagePlan.WEEK / 2, MY));
        shortPlan.mode = "weighted";
        shortPlan.weights = new double[]{2, 1, 1, 1, 1, 0, 0};
        near(50, shortPlan.expected(start + UsagePlan.FIVE_HOURS, UsagePlan.FIVE_HOURS, start + UsagePlan.FIVE_HOURS / 2, MY));
        long crossing = start + 22 * UsagePlan.HOUR;
        near(400.0 / 7, shortPlan.expected(crossing + UsagePlan.FIVE_HOURS, UsagePlan.FIVE_HOURS, crossing + 2 * UsagePlan.HOUR, MY));
        long saturday = start + 5 * 24 * UsagePlan.HOUR;
        near(0, shortPlan.expected(saturday + UsagePlan.FIVE_HOURS, UsagePlan.FIVE_HOURS, saturday + 2 * UsagePlan.HOUR, MY));
        // A real DST day must not introduce a gap, loop, or backwards budget.
        ZoneId ny = ZoneId.of("America/New_York");
        long dstStart = time("2026-03-08T05:00:00Z");
        near(50, p.expected(dstStart + UsagePlan.WEEK, UsagePlan.WEEK, dstStart + UsagePlan.WEEK / 2, ny));
        double previous = -1;
        p.weights = new double[]{2, 1, 1, 1, 1, 0, 1};
        for (int i = 0; i <= 168; i++) {
            double next = p.expected(dstStart + UsagePlan.WEEK, UsagePlan.WEEK, dstStart + i * UsagePlan.HOUR, ny);
            check(next >= previous && next >= 0 && next <= 100, "DST budget monotonic"); previous = next;
        }
        p.mode = "reserve"; p.reserveHours = 48; p.reserve = 30;
        long deadline = reset - 48 * UsagePlan.HOUR;
        near(70, p.expected(reset, UsagePlan.WEEK, deadline, MY));
        near(35, p.expected(reset, UsagePlan.WEEK, start + (deadline - start) / 2, MY));
        near(85, p.expected(reset, UsagePlan.WEEK, reset - 24 * UsagePlan.HOUR, MY));
        near(100, p.expected(reset, UsagePlan.WEEK, reset, MY));
        shortPlan.mode = "reserve";
        near(70, shortPlan.expected(start + UsagePlan.FIVE_HOURS, UsagePlan.FIVE_HOURS, start + 4 * UsagePlan.HOUR, MY));
        p.enabled = false; p.over = 12.5; p.under = 8; p.step = 6; p.underEnabled = false;
        check(p.json().equals(UsagePlan.parse(p.json(), true).json()), "all settings survive serialization");
        near(48, UsagePlan.parse("corrupt", true).reserveHours);
        near(1, UsagePlan.parse("", false).reserveHours);
        check(UsagePlan.duration("每週") == UsagePlan.WEEK, "weekly label");
        check(UsagePlan.duration("7 天") == UsagePlan.WEEK, "7-day label");
        check(UsagePlan.duration("5 小時") == UsagePlan.FIVE_HOURS, "five-hour label");
        check(UsagePlan.duration("15 分鐘") == 0, "unknown short window rejected");
        check(UsagePlan.duration("30 天") == 0, "unknown long window rejected");
        check(UsagePlan.duration("15 小時") == 0, "do not mistake 15h for 5h");
        p = new UsagePlan(true); p.over = 0; invalid(p);
        p = new UsagePlan(true); p.step = Double.NaN; invalid(p);
        p = new UsagePlan(true); p.weights = new double[7]; invalid(p);
        p = new UsagePlan(true); p.weights[0] = -1; invalid(p);
        p = new UsagePlan(true); p.reserve = 100; invalid(p);
        p = new UsagePlan(true); p.reserveHours = 168; invalid(p);
        p = new UsagePlan(true); p.mode = "unsupported"; invalid(p);
        System.out.println(count + " usage-plan assertions passed.");
    }
}
