package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Hand-computed cases for the attention calculator; the SQL comparison lives in the parity and golden tests. */
class AttentionCalculatorTest {

    // The 12-month window used below has magnitudes 0.0006 x7, 0.0004 x3, 0.0007 and 0.0005: mean 0.00055, sample standard deviation exactly 0.0001.

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static List<MonthReturn> rows(String start, String... values) {
        List<MonthReturn> out = new ArrayList<>();
        YearMonth month = YearMonth.parse(start);
        for (String value : values) {
            out.add(new MonthReturn(month, d(value)));
            month = month.plusMonths(1);
        }
        return out;
    }

    @Test
    void A_U1_창이_차기_전에는_z와_지수가_없고_열세_번째_달에_손계산_값이_나온다() {
        // magnitudes 0.0006 x7, 0.0004 x3, 0.0007, 0.0005 → mean 0.00055, sd 0.0001; 13th = 0.00075 → z = 2
        List<MonthReturn> input = rows("2000-01", "0.00060000", "0.00060000", "0.00060000", "0.00060000", "0.00060000",
                "0.00060000", "0.00060000", "0.00040000", "0.00040000", "0.00040000", "0.00070000", "0.00050000", "0.00075000");
        List<AttentionValue> out = AttentionCalculator.series(input);

        assertThat(out).hasSize(13);
        for (int i = 0; i < 12; i++) {
            assertThat(out.get(i).windowMonths()).as("row %d", i).isEqualTo(i);
            assertThat(out.get(i).attentionZ()).isNull();
            assertThat(out.get(i).attentionIndex()).isNull();
        }
        AttentionValue last = out.get(12);
        assertThat(last.windowMonths()).isEqualTo(12);
        assertThat(last.attentionZ()).isEqualByComparingTo("2.00000000");
        assertThat(last.attentionZ().scale()).isEqualTo(8);
        assertThat(last.streak()).isEqualTo(13);
        // streak 13 is past the cap of 5: bonus 0.25 x 4 = 1.00
        assertThat(last.attentionIndex()).isEqualByComparingTo("3.00000000");
    }

    @Test
    void A_U2_표준편차가_하한과_같으면_z를_계산하고_바로_아래면_널이다() {
        List<MonthReturn> atFloor = rows("2000-01", "0.00060000", "0.00060000", "0.00060000", "0.00060000", "0.00060000",
                "0.00060000", "0.00060000", "0.00040000", "0.00040000", "0.00040000", "0.00070000", "0.00050000", "0.00075000");
        assertThat(AttentionCalculator.series(atFloor).get(12).attentionZ()).isNotNull();

        // one value lowered by 1e-8: deviation 0.0000999985... is under the floor
        List<MonthReturn> below = new ArrayList<>(atFloor);
        below.set(10, new MonthReturn(below.get(10).month(), d("0.00069999")));
        assertThat(AttentionCalculator.series(below).get(12).attentionZ()).isNull();
        assertThat(AttentionCalculator.series(below).get(12).attentionIndex()).isNull();
    }

    @Test
    void A_U2_열두_달이_모두_같으면_표준편차_0이라_널이고_예외가_없다() {
        List<MonthReturn> flat = new ArrayList<>();
        YearMonth month = YearMonth.of(2000, 1);
        for (int i = 0; i < 14; i++) {
            flat.add(new MonthReturn(month, d("0.01000000")));
            month = month.plusMonths(1);
        }
        List<AttentionValue> out = AttentionCalculator.series(flat);
        assertThat(out.get(12).windowMonths()).isEqualTo(12);
        assertThat(out.get(12).attentionZ()).isNull();
        assertThat(out.get(13).attentionZ()).isNull();
    }

    @Test
    void A_U3_z가_3을_넘으면_3_아래면_마이너스_3으로_자른다() {
        // magnitudes x 100: mean 0.055, sd 0.01; a 0.30 return gives z = 24.5, a 0 return gives z = -5.5
        String[] w = {"0.06000000", "0.06000000", "0.06000000", "0.06000000", "0.06000000", "0.06000000", "0.06000000",
                "0.04000000", "0.04000000", "0.04000000", "0.07000000", "0.05000000"};
        List<MonthReturn> up = new ArrayList<>(rows("2000-01", w));
        up.add(new MonthReturn(YearMonth.of(2001, 1), d("0.30000000")));
        List<MonthReturn> down = new ArrayList<>(rows("2000-01", w));
        down.add(new MonthReturn(YearMonth.of(2001, 1), d("0.00000000")));

        BigDecimal plus = AttentionCalculator.series(up).get(12).attentionZ();
        BigDecimal minus = AttentionCalculator.series(down).get(12).attentionZ();
        assertThat(plus).isEqualByComparingTo("3.00000000");
        assertThat(plus.scale()).isEqualTo(8);
        assertThat(minus).isEqualByComparingTo("-3.00000000");
        assertThat(minus.scale()).isEqualTo(8);
    }

    @Test
    void A_U4_연속_달은_부호가_바뀌거나_0_수익률이거나_달이_비면_다시_센다() {
        // 6 gains, a zero, a gain, 2 losses; then 2000-11 is missing and 2000-12 starts a new segment
        List<MonthReturn> input = new ArrayList<>(rows("2000-01", "0.01000000", "0.02000000", "0.03000000", "0.01000000",
                "0.02000000", "0.03000000", "0.00000000", "0.01000000", "-0.01000000", "-0.02000000"));
        input.add(new MonthReturn(YearMonth.of(2000, 12), d("-0.03000000")));
        List<AttentionValue> out = AttentionCalculator.series(input);

        assertThat(out).extracting(AttentionValue::streak).containsExactly(1, 2, 3, 4, 5, 6, 0, 1, -1, -2, -1);
        assertThat(out).extracting(AttentionValue::windowMonths).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0);
    }

    @Test
    void A_U5_지수_보너스는_연속_1_이하는_0_5_이상은_1_00으로_멈춘다() {
        for (int sign : new int[] {1, -1}) {
            for (int length = 1; length <= 7; length++) {
                // the last (length - 1) magnitudes of the window and the 13th return share the sign, the rest have the other sign
                long[] raw = {60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 40_000, 40_000, 40_000, 70_000, 50_000};
                List<MonthReturn> input = new ArrayList<>();
                YearMonth month = YearMonth.of(2000, 1);
                for (int i = 0; i < raw.length; i++) {
                    int rowSign = i >= raw.length - (length - 1) ? sign : -sign;
                    input.add(new MonthReturn(month, BigDecimal.valueOf(rowSign * raw[i], 8)));
                    month = month.plusMonths(1);
                }
                input.add(new MonthReturn(month, BigDecimal.valueOf(sign * 75_000L, 8)));
                AttentionValue last = AttentionCalculator.series(input).get(12);

                BigDecimal bonus = new BigDecimal("0.25").multiply(BigDecimal.valueOf(Math.min(length, 5) - 1));
                assertThat(last.streak()).as("sign %d length %d", sign, length).isEqualTo(sign * length);
                assertThat(last.attentionZ()).isEqualByComparingTo("2.00000000");
                assertThat(last.attentionIndex()).isEqualByComparingTo(new BigDecimal("2").add(bonus));
            }
        }
    }

    @Test
    void A_U5_streak_0이면_보너스가_없고_z가_없으면_지수도_없다() {
        List<MonthReturn> input = rows("2000-01", "0.00060000", "0.00060000", "0.00060000", "0.00060000", "0.00060000",
                "0.00060000", "0.00060000", "0.00040000", "0.00040000", "0.00040000", "0.00070000", "0.00050000", "0.00000000");
        AttentionValue last = AttentionCalculator.series(input).get(12);
        assertThat(last.streak()).isZero();
        // z = (0 - 0.00055) / 0.0001 = -5.5 → clipped to -3, no bonus
        assertThat(last.attentionZ()).isEqualByComparingTo("-3.00000000");
        assertThat(last.attentionIndex()).isEqualByComparingTo("-3.00000000");
    }

    @Test
    void A_U5_지수는_음수_3에서_4_사이다() {
        Random random = new Random(7L);
        List<MonthReturn> input = new ArrayList<>();
        YearMonth month = YearMonth.of(2000, 1);
        for (int i = 0; i < 80; i++) {
            input.add(new MonthReturn(month, BigDecimal.valueOf(random.nextInt(24_000_001) - 12_000_000L, 8)));
            month = month.plusMonths(1);
        }
        for (AttentionValue v : AttentionCalculator.series(input)) {
            if (v.attentionIndex() != null) {
                assertThat(v.attentionIndex()).isBetween(new BigDecimal("-3"), new BigDecimal("4"));
            }
        }
    }

    private static List<MonthReturn> random(Random random, YearMonth start, int count) {
        List<MonthReturn> out = new ArrayList<>();
        YearMonth month = start;
        for (int i = 0; i < count; i++) {
            out.add(new MonthReturn(month, BigDecimal.valueOf(random.nextInt(24_000_001) - 12_000_000L, 8)));
            month = month.plusMonths(1);
        }
        return out;
    }

    @Test
    void A_U6_미래_달을_바꾸거나_더해도_그_달까지의_행은_그대로다() {
        Random random = new Random(11L);
        List<MonthReturn> base = random(random, YearMonth.of(2000, 1), 40);
        List<AttentionValue> expected = AttentionCalculator.series(base);
        for (int m = 0; m < base.size(); m++) {
            List<MonthReturn> changed = new ArrayList<>(base.subList(0, m + 1));
            changed.addAll(random(new Random(100L + m), base.get(m).month().plusMonths(1), base.size() - m - 1 + 5));
            List<AttentionValue> actual = AttentionCalculator.series(changed);
            assertThat(actual.subList(0, m + 1)).as("rows up to month index %d", m).isEqualTo(expected.subList(0, m + 1));
        }
    }

    @Test
    void A_U6_창_안_달을_바꾸면_그_달의_행이_바뀐다() {
        Random random = new Random(11L);
        List<MonthReturn> base = random(random, YearMonth.of(2000, 1), 40);
        int m = 30;
        List<MonthReturn> changed = new ArrayList<>(base);
        changed.set(m - 3, new MonthReturn(base.get(m - 3).month(), base.get(m - 3).localReturn().add(d("0.05000000"))));
        List<AttentionValue> before = AttentionCalculator.series(base);
        List<AttentionValue> after = AttentionCalculator.series(changed);
        assertThat(before.get(m).attentionZ()).isNotNull();
        assertThat(after.get(m).attentionZ()).isNotNull().isNotEqualByComparingTo(before.get(m).attentionZ());
        // rows before the changed month are untouched
        assertThat(after.subList(0, m - 3)).isEqualTo(before.subList(0, m - 3));
    }

    /** Window magnitudes in units of 1e-4: mean 0.0176 and sample standard deviation exactly 0.0032 (= 32 x 0.0001). */
    private static final long[] TIE_WINDOW = {192, 192, 192, 192, 192, 192, 192, 128, 128, 128, 224, 160};

    /** The 13th month's z for a window given in 1e-8 units and a 13th return in 1e-8 units. */
    private static BigDecimal thirteenthZ(long[] windowUnits, long currentUnits) {
        List<MonthReturn> input = new ArrayList<>();
        YearMonth month = YearMonth.of(2000, 1);
        for (long unit : windowUnits) {
            input.add(new MonthReturn(month, BigDecimal.valueOf(unit, 8)));
            month = month.plusMonths(1);
        }
        input.add(new MonthReturn(month, BigDecimal.valueOf(currentUnits, 8)));
        return AttentionCalculator.series(input).get(12).attentionZ();
    }

    private static long[] tieWindow(int index, long deltaUnits) {
        long[] units = new long[TIE_WINDOW.length];
        for (int i = 0; i < units.length; i++) {
            units[i] = TIE_WINDOW[i] * 10_000L;
        }
        units[index] += deltaUnits;
        return units;
    }

    @Test
    void A_U7_z가_9자리에서_정확히_5로_끝나는_동률은_0에서_먼_쪽으로_반올림한다() {
        // z = k x 1e-8 / 0.0032 = k x 0.000003125: odd k ends exactly at the 9th digit with a 5, even k has no 9th digit
        long[] window = tieWindow(0, 0);
        long mean = 1_760_000L;
        Object[][] cases = {
                {-3L, "-0.00000938"}, {-2L, "-0.00000625"}, {-1L, "-0.00000313"}, {1L, "0.00000313"}, {2L, "0.00000625"},
                {3L, "0.00000938"}, {4L, "0.00001250"}};
        for (Object[] c : cases) {
            BigDecimal z = thirteenthZ(window, mean + (Long) c[0]);
            assertThat(z).as("k = %d", c[0]).isEqualByComparingTo((String) c[1]);
            assertThat(z.scale()).isEqualTo(8);
        }
    }

    @Test
    void A_U7_z가_9자리_반올림_동률_바로_아래이면_8자리로_한_번만_반올림한다() {
        // exact z 0.0000028645829... and -0.0001216146006...: rounding first to 9 digits would give ...287 and ...162
        assertThat(thirteenthZ(tieWindow(0, 1), 1_760_001L)).isEqualByComparingTo("0.00000286");
        assertThat(thirteenthZ(tieWindow(0, -1), 1_759_961L)).isEqualByComparingTo("-0.00012161");
    }

    @Test
    void A_U7_큰_공통값에_작은_편차가_있어도_z는_정확하다() {
        // the window of A-U1 shifted by 5000: same deviations, so the same z of 2 (a lost digit would move it)
        long big = 500_000_000_000L;
        long[] window = {60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 40_000, 40_000, 40_000, 70_000, 50_000};
        for (int i = 0; i < window.length; i++) {
            window[i] += big;
        }
        assertThat(thirteenthZ(window, big + 75_000L)).isEqualByComparingTo("2.00000000");
        long[] below = window.clone();
        below[10] -= 1;
        assertThat(thirteenthZ(below, big + 75_000L)).isNull();
    }

    @Test
    void A_N2_MonthReturn은_NUMERIC_12_8로_읽은_값만_받고_다른_자릿수와_범위는_거부한다() {
        YearMonth month = YearMonth.of(2000, 1);
        assertThat(new MonthReturn(month, d("-1.00000000")).localReturn()).isEqualTo(d("-1.00000000"));
        assertThat(new MonthReturn(month, d("9999.99999999")).localReturn()).isEqualTo(d("9999.99999999"));
        for (String bad : new String[] {"0.1", "0.100000000", "0.01", "-1.00000001", "-2.00000000", "10000.00000000"}) {
            assertThatThrownBy(() -> new MonthReturn(month, d(bad))).as(bad.length() + " chars").isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void A_N1_입력이_잘못되면_IllegalArgumentException이다() {
        assertThatThrownBy(() -> AttentionCalculator.series(null)).isInstanceOf(IllegalArgumentException.class);
        List<MonthReturn> withNull = new ArrayList<>();
        withNull.add(new MonthReturn(YearMonth.of(2000, 1), d("0.01000000")));
        withNull.add(null);
        assertThatThrownBy(() -> AttentionCalculator.series(withNull)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonthReturn(YearMonth.of(2000, 1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonthReturn(null, d("0.01000000"))).isInstanceOf(IllegalArgumentException.class);
        List<MonthReturn> duplicate = List.of(new MonthReturn(YearMonth.of(2000, 1), d("0.01000000")),
                new MonthReturn(YearMonth.of(2000, 1), d("0.02000000")));
        assertThatThrownBy(() -> AttentionCalculator.series(duplicate)).isInstanceOf(IllegalArgumentException.class);
        List<MonthReturn> descending = new ArrayList<>(rows("2000-01", "0.01000000", "0.02000000"));
        java.util.Collections.reverse(descending);
        assertThatThrownBy(() -> AttentionCalculator.series(descending)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void A_N1_빈_목록은_빈_결과다() {
        assertThat(AttentionCalculator.series(List.of())).isEmpty();
    }
}
