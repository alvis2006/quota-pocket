import com.gather.quotapocket.QuotaMath;

public final class QuotaMathTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(QuotaMath.remaining(100) == 0);
        check(QuotaMath.remaining(0) == 100);
        check(QuotaMath.remaining(59) == 41);
        check(QuotaMath.resetText(0, 1).equals("未提供重置時間"));
        check(QuotaMath.resetText(1000, 1000).contains("待更新"));
        check(QuotaMath.resetText(999, 1000).contains("待更新"));
        check(QuotaMath.resetText(60_001, 1).equals("1 分後重置"));
        check(QuotaMath.resetText(3_600_001, 1).equals("1 時 0 分後重置"));
        check(QuotaMath.resetText(90_000_001, 1).equals("1 天 1 時後重置"));
        check(!QuotaMath.stale(100, 3_600_100));
        check(QuotaMath.stale(100, 3_600_101));
        for (double value : new double[]{-1, 101, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean threw = false;
            try { QuotaMath.remaining(value); } catch (IllegalArgumentException e) { threw = true; }
            check(threw);
        }
        check(QuotaMath.ringReset(0, 1, false).equals("時間未知"));
        check(QuotaMath.ringReset(1, 1, true).equals("待更新"));
        check(QuotaMath.ringReset(45 * 60_000, 1, false).equals("45分"));
        check(QuotaMath.ringReset((2 * 60 + 14) * 60_000, 0, false).equals("2時14分"));
        check(QuotaMath.ringReset((4 * 60 + 2) * 60_000, 0, true).equals("4時02分 後重置"));
        check(QuotaMath.ringReset(3 * 86_400_000L, 0, false).equals("3天"));
        check(QuotaMath.ringReset(3 * 86_400_000L + 5 * 3_600_000L, 0, true).equals("3天5時 後重置"));
        check(QuotaMath.weekly("每週") && QuotaMath.weekly("7 天") && QuotaMath.weekly("Weekly"));
        check(!QuotaMath.weekly("5 小時") && !QuotaMath.weekly("15 分鐘"));
        System.out.println("24 quota math assertions passed.");
    }
}
