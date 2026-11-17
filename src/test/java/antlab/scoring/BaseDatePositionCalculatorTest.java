package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Currency;
import antlab.asset.Position;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Hand-built cases for the base-date position calculator. The market is controlled: with a zero US proxy return and zero FX, the market
 * return is half the KR proxy return, and every sixth month (counted from the window start 2023-01) is up (+0.02), every sixth from the
 * fourth month is down (-0.02), the rest are neutral (+0.001). The SQL comparison lives in the parity and golden tests.
 */
class BaseDatePositionCalculatorTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final YearMonth WINDOW_START = YearMonth.of(2023, 1);
    private static final YearMonth WINDOW_END = YearMonth.of(2025, 12);

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static int index(YearMonth month) {
        return Math.floorMod((int) ChronoUnit.MONTHS.between(WINDOW_START, month), 6);
    }

    private static boolean isUp(YearMonth month) {
        return index(month) == 1;
    }

    private static boolean isDown(YearMonth month) {
        return index(month) == 4;
    }

    /** Blocks, the two proxies and the USD changes over a month range. */
    private static final class Fixture {
        final NavigableSet<YearMonth> blocks = new TreeSet<>();
        final NavigableMap<YearMonth, BigDecimal> kr = new TreeMap<>();
        final NavigableMap<YearMonth, BigDecimal> us = new TreeMap<>();
        final Map<YearMonth, BigDecimal> fx = new HashMap<>();

        Fixture(YearMonth from, YearMonth to) {
            for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
                blocks.add(m);
                kr.put(m, isUp(m) ? d("0.04000000") : isDown(m) ? d("-0.04000000") : d("0.00200000"));
                us.put(m, d("0.00000000"));
                fx.put(m, d("0.00000000"));
            }
        }

        PositionHistory history() {
            return new PositionHistory(new TreeSet<>(blocks), Map.of(Currency.USD, new HashMap<>(fx)),
                    new AssetSeries(Currency.KRW, new TreeMap<>(kr)), new AssetSeries(Currency.USD, new TreeMap<>(us)));
        }

        /** Market return of a month, worked out here independently of the calculator. */
        BigDecimal market(YearMonth m) {
            BigDecimal usKrw = BigDecimal.ONE.add(us.get(m)).multiply(BigDecimal.ONE.add(fx.get(m))).subtract(BigDecimal.ONE);
            return d("0.5").multiply(kr.get(m)).add(d("0.5").multiply(usKrw));
        }

        AssetSeries asset(Currency currency, Predicate<YearMonth> keep, Function<YearMonth, BigDecimal> rule) {
            NavigableMap<YearMonth, BigDecimal> out = new TreeMap<>();
            for (YearMonth m : blocks) {
                if (keep.test(m)) {
                    out.put(m, rule.apply(m));
                }
            }
            return new AssetSeries(currency, out);
        }

        /** An asset whose return is {@code p} times the market return in every month. */
        AssetSeries times(String p) {
            return asset(Currency.KRW, m -> true, m -> d(p).multiply(market(m)));
        }
    }

    private static Fixture standard() {
        return new Fixture(YearMonth.of(2022, 10), YearMonth.of(2026, 3));
    }

    private static Map<String, BaseDateJudgement> judge(Fixture f, Map<String, AssetSeries> pool) {
        return BaseDatePositionCalculator.judge(BASE, pool, f.history());
    }

    private static Map<String, AssetSeries> pool(Object... keyAndSeries) {
        Map<String, AssetSeries> out = new LinkedHashMap<>();
        for (int i = 0; i < keyAndSeries.length; i += 2) {
            out.put((String) keyAndSeries[i], (AssetSeries) keyAndSeries[i + 1]);
        }
        return out;
    }

    @Test
    void P_U1_창은_기준일이_속한_달의_바로_전_달에서_끝나고_달력_경계로_밀린다() {
        Fixture f = standard();
        Map<String, AssetSeries> pool = pool("A", f.times("1.2"));

        BaseDateJudgement first = BaseDatePositionCalculator.judge(LocalDate.of(2026, 1, 2), pool, f.history()).get("A");
        BaseDateJudgement lastDay = BaseDatePositionCalculator.judge(LocalDate.of(2026, 1, 31), pool, f.history()).get("A");
        BaseDateJudgement nextMonth = BaseDatePositionCalculator.judge(LocalDate.of(2026, 2, 1), pool, f.history()).get("A");

        assertThat(first.windowStart()).isEqualTo(YearMonth.of(2023, 1));
        assertThat(first.windowEnd()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(first.monthsUsed()).isEqualTo(36);
        assertThat(lastDay.windowStart()).isEqualTo(first.windowStart());
        assertThat(lastDay.windowEnd()).isEqualTo(first.windowEnd());
        assertThat(nextMonth.windowStart()).isEqualTo(YearMonth.of(2023, 2));
        assertThat(nextMonth.windowEnd()).isEqualTo(YearMonth.of(2026, 1));
    }

    @Test
    void P_U2_사용_달은_종목과_시장이_함께_있는_달이고_시장_낙폭은_창_안_모든_월_블록으로_잰다() {
        Fixture f = standard();
        YearMonth hole = WINDOW_START.plusMonths(20);
        // the market falls 30 % in a month the asset has no return for
        f.kr.put(hole, d("-0.60000000"));
        AssetSeries asset = f.asset(Currency.KRW, m -> !m.equals(hole),
                m -> isUp(m) ? d("0.03000000") : isDown(m) ? d("-0.05000000") : d("0.00100000"));

        BaseDateJudgement got = judge(f, pool("A", asset)).get("A");

        assertThat(got.monthsUsed()).isEqualTo(35);
        List<BigDecimal> own = new ArrayList<>();
        for (YearMonth m = WINDOW_START; !m.isAfter(WINDOW_END); m = m.plusMonths(1)) {
            if (!m.equals(hole)) {
                own.add(asset.localReturns().get(m));
            }
        }
        assertThat(got.maxDrawdown()).isEqualByComparingTo(PositionMeter.maxDrawdown(own));
        // up participation 1.5 and a top volatility, but the asset's drawdown is shallow against 1.5 x the market's 30 % drop
        assertThat(got.upParticipation()).isEqualByComparingTo("1.5");
        assertThat(got.systemPosition()).isEqualTo(Position.MIDFIELDER);
    }

    @Test
    void P_U3_외화_종목은_환율을_곱해_원화로_바꾸고_원화_종목은_환율을_보지_않는다() {
        Fixture f = standard();
        int i = 0;
        for (YearMonth m : f.blocks) {
            f.fx.put(m, i++ % 2 == 0 ? d("0.01000000") : d("-0.00500000"));
        }
        AssetSeries usd = f.asset(Currency.USD, m -> true, m -> isUp(m) ? d("0.03000000") : isDown(m) ? d("-0.02000000") : d("0.00500000"));
        AssetSeries krw = f.asset(Currency.KRW, m -> true, m -> isUp(m) ? d("0.03000000") : isDown(m) ? d("-0.02000000") : d("0.00500000"));

        Map<String, BaseDateJudgement> got = judge(f, pool("USD", usd, "KRW", krw));

        List<BigDecimal> market = new ArrayList<>();
        List<BigDecimal> usdKrw = new ArrayList<>();
        List<BigDecimal> krwOnly = new ArrayList<>();
        for (YearMonth m = WINDOW_START; !m.isAfter(WINDOW_END); m = m.plusMonths(1)) {
            market.add(f.market(m));
            usdKrw.add(BigDecimal.ONE.add(usd.localReturns().get(m)).multiply(BigDecimal.ONE.add(f.fx.get(m))).subtract(BigDecimal.ONE));
            krwOnly.add(krw.localReturns().get(m));
        }
        assertSameMetrics(got.get("USD"), PositionMeter.measure(usdKrw, market, 24, 5, 5));
        assertSameMetrics(got.get("KRW"), PositionMeter.measure(krwOnly, market, 24, 5, 5));
        assertThat(got.get("USD").volatility()).isNotEqualByComparingTo(got.get("KRW").volatility());
    }

    private static void assertSameMetrics(BaseDateJudgement got, PositionMeter.Metrics want) {
        assertThat(want.judgeable()).isTrue();
        assertThat(got.monthsUsed()).isEqualTo(want.monthsUsed());
        assertThat(got.upParticipation()).isEqualTo(want.up());
        assertThat(got.downParticipation()).isEqualTo(want.down());
        assertThat(got.volatility()).isEqualTo(want.volatility());
        assertThat(got.maxDrawdown()).isEqualTo(want.maxDrawdown());
    }

    /** An asset that exists in {@code ups} up months, {@code downs} down months and {@code neutrals} neutral months of the window (first ones). */
    private static AssetSeries subset(Fixture f, int ups, int downs, int neutrals) {
        int[] left = {ups, downs, neutrals};
        return f.asset(Currency.KRW, m -> {
            if (m.isBefore(WINDOW_START) || m.isAfter(WINDOW_END)) {
                return false;
            }
            int slot = isUp(m) ? 0 : isDown(m) ? 1 : 2;
            return left[slot]-- > 0;
        }, m -> d("1.1").multiply(f.market(m)).add(m.getMonthValue() % 2 == 0 ? d("0.00300000") : d("-0.00200000")));
    }

    @Test
    void P_U4_최소_달_수와_오른_달_빠진_달에_못_미치면_사유와_함께_8컬럼이_모두_널이다() {
        Fixture f = standard();
        Map<String, BaseDateJudgement> got = judge(f, pool(
                "OK", subset(f, 5, 5, 14),
                "MONTHS", subset(f, 5, 5, 13),
                "N_UP", subset(f, 4, 6, 14),
                "N_DOWN", subset(f, 6, 4, 14),
                "MONTHS_FIRST", subset(f, 4, 5, 14)));

        assertThat(got.get("OK").judgeable()).isTrue();
        assertThat(got.get("OK").monthsUsed()).isEqualTo(24);
        assertThat(got.get("OK").nUp()).isEqualTo(5);
        assertThat(got.get("OK").nDown()).isEqualTo(5);
        assertThat(got.get("MONTHS").reason()).isEqualTo(PositionMeter.Reason.MONTHS);
        assertThat(got.get("N_UP").reason()).isEqualTo(PositionMeter.Reason.N_UP);
        assertThat(got.get("N_DOWN").reason()).isEqualTo(PositionMeter.Reason.N_DOWN);
        assertThat(got.get("MONTHS_FIRST").reason()).as("MONTHS is checked before N_UP").isEqualTo(PositionMeter.Reason.MONTHS);
        for (String key : List.of("MONTHS", "N_UP", "N_DOWN", "MONTHS_FIRST")) {
            BaseDateJudgement j = got.get(key);
            assertThat(j.judgeable()).isFalse();
            assertThat(j.windowStart()).isNull();
            assertThat(j.windowEnd()).isNull();
            assertThat(j.monthsUsed()).isNull();
            assertThat(j.upParticipation()).isNull();
            assertThat(j.downParticipation()).isNull();
            assertThat(j.volatility()).isNull();
            assertThat(j.maxDrawdown()).isNull();
            assertThat(j.systemPosition()).isNull();
        }
    }

    @Test
    void P_U4b_분모가_0인_사유_ZERO_DEN은_기준일_최소_달_수에서는_닿지_않고_N_UP_N_DOWN이_먼저_멈춘다() {
        // A band month moves the market by at least 0.01, so five of them never sum to zero: with the base-date minimums (5 and 5)
        // a zero denominator is unreachable and the count checks stop first.
        Fixture noUp = standard();
        noUp.kr.replaceAll((m, v) -> isDown(m) ? d("-0.04000000") : d("0.00200000"));
        Fixture noDown = standard();
        noDown.kr.replaceAll((m, v) -> isUp(m) ? d("0.04000000") : d("0.00200000"));

        BaseDateJudgement withoutUp = judge(noUp, pool("A", noUp.times("1.2"))).get("A");
        BaseDateJudgement withoutDown = judge(noDown, pool("A", noDown.times("1.2"))).get("A");

        assertThat(withoutUp.reason()).isEqualTo(PositionMeter.Reason.N_UP);
        assertThat(withoutUp.nUp()).isZero();
        assertThat(withoutDown.reason()).isEqualTo(PositionMeter.Reason.N_DOWN);
        assertThat(withoutDown.nDown()).isZero();

        // Five months exactly on the band edge are enough, and their sum is not zero.
        Fixture edge = standard();
        edge.kr.replaceAll((m, v) -> isUp(m) ? d("0.02000000") : isDown(m) ? d("-0.02000000") : d("0.00200000"));
        BaseDateJudgement atEdge = judge(edge, pool("A", edge.times("1.2"))).get("A");
        assertThat(atEdge.judgeable()).isTrue();
        assertThat(atEdge.nUp()).isEqualTo(6);
        assertThat(atEdge.nDown()).isEqualTo(6);
    }

    @Test
    void P_U4c_ZERO_DEN_사유의_판정_불능_행은_8컬럼이_모두_널이고_사유와_달_수를_그대로_담는다() {
        // The reason itself is produced by PositionMeter.measure when the minimums are lowered (PositionMeterTest); the row type must carry it.
        BaseDateJudgement row = BaseDateJudgement.unjudged(PositionMeter.Reason.ZERO_DEN, 0, 3);

        assertThat(row.judgeable()).isFalse();
        assertThat(row.reason()).isEqualTo(PositionMeter.Reason.ZERO_DEN);
        assertThat(row.nUp()).isZero();
        assertThat(row.nDown()).isEqualTo(3);
        assertThat(row.windowStart()).isNull();
        assertThat(row.windowEnd()).isNull();
        assertThat(row.monthsUsed()).isNull();
        assertThat(row.upParticipation()).isNull();
        assertThat(row.downParticipation()).isNull();
        assertThat(row.volatility()).isNull();
        assertThat(row.maxDrawdown()).isNull();
        assertThat(row.systemPosition()).isNull();
        assertThat(PositionMeter.measure(java.util.Collections.nCopies(30, d("0.00000000")), java.util.Collections.nCopies(30, d("0.00000000")),
                ReportRules.BASE_MIN_MONTHS, 0, 0).reason()).isEqualTo(PositionMeter.Reason.ZERO_DEN);
    }

    @Test
    void P_U5_순위는_같은_기준일의_판정_가능_종목끼리만_세고_경계_동률은_모두_상위다() {
        Fixture f = standard();
        Map<String, AssetSeries> six = pool("P30", f.times("3.0"), "P25A", f.times("2.5"), "P25B", f.times("2.5"),
                "P12", f.times("1.2"), "P09", f.times("0.9"), "P08", f.times("0.8"));
        Map<String, BaseDateJudgement> alone = judge(f, six);

        // ten assets that cannot be judged (too few months) with huge returns must change neither N nor k
        Map<String, AssetSeries> crowded = new LinkedHashMap<>(six);
        for (int i = 0; i < 10; i++) {
            crowded.put("X" + i, f.asset(Currency.KRW, m -> !m.isBefore(WINDOW_END.minusMonths(9)) && !m.isAfter(WINDOW_END),
                    m -> d("0.9").multiply(isUp(m) ? d("1") : d("-1"))));
        }
        Map<String, BaseDateJudgement> withUnjudged = judge(f, crowded);

        for (String key : six.keySet()) {
            assertThat(withUnjudged.get(key)).as(key).isEqualTo(alone.get(key));
        }
        assertThat(withUnjudged.get("X0").reason()).isEqualTo(PositionMeter.Reason.MONTHS);
        // N = 6 so k = 2: P12 is fourth by volatility (outside the top), the tie at the second place is inside it; counting the ten unjudged rows would make k = 4
        assertThat(alone.get("P12").systemPosition()).isEqualTo(Position.FORWARD_TARGET);
        assertThat(alone.get("P25A").volatility()).isEqualByComparingTo(alone.get("P25B").volatility());
        assertThat(alone.get("P25A").systemPosition()).isEqualTo(Position.FORWARD_BREAKOUT);
        assertThat(alone.get("P25B").systemPosition()).isEqualTo(Position.FORWARD_BREAKOUT);
        assertThat(alone.get("P30").systemPosition()).isEqualTo(Position.FORWARD_BREAKOUT);
    }

    @Test
    void P_U6_기준일_달과_그_뒤의_수익률과_환율은_결과를_바꾸지_않고_창_끝_달은_바꾼다() {
        Fixture f = standard();
        Map<String, AssetSeries> pool = pool("A", f.times("1.2"), "B", f.times("0.5"), "C", f.times("2.0"));
        Map<String, BaseDateJudgement> before = judge(f, pool);

        Fixture later = standard();
        Map<String, AssetSeries> changed = new LinkedHashMap<>();
        for (Map.Entry<String, AssetSeries> entry : pool.entrySet()) {
            NavigableMap<YearMonth, BigDecimal> copy = new TreeMap<>(entry.getValue().localReturns());
            for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2027, 1)); m = m.plusMonths(1)) {
                copy.put(m, d("-0.90000000"));
            }
            changed.put(entry.getKey(), new AssetSeries(Currency.KRW, copy));
        }
        for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2027, 1)); m = m.plusMonths(1)) {
            later.kr.put(m, d("0.50000000"));
            later.us.put(m, d("0.40000000"));
            later.fx.put(m, d("0.30000000"));
            later.blocks.add(m);
        }
        later.blocks.add(YearMonth.of(2027, 2));
        later.kr.put(YearMonth.of(2027, 2), d("0.10000000"));
        later.us.put(YearMonth.of(2027, 2), d("0.10000000"));
        later.fx.put(YearMonth.of(2027, 2), d("0.10000000"));

        assertThat(BaseDatePositionCalculator.judge(BASE, changed, later.history())).isEqualTo(before);

        NavigableMap<YearMonth, BigDecimal> endChanged = new TreeMap<>(pool.get("A").localReturns());
        endChanged.put(WINDOW_END, d("-0.30000000"));
        Map<String, AssetSeries> otherEnd = new LinkedHashMap<>(pool);
        otherEnd.put("A", new AssetSeries(Currency.KRW, endChanged));
        assertThat(judge(f, otherEnd).get("A")).isNotEqualTo(before.get("A"));
    }

    @Test
    void P_N1_창_안에_환율이나_시장_대용_수익률이_없으면_멈추고_창_밖은_보지_않는다() {
        Fixture noFx = standard();
        Map<String, AssetSeries> noFxPool = pool("A", noFx.times("1.2"));
        noFx.fx.remove(WINDOW_START.plusMonths(7));
        assertThatThrownBy(() -> judge(noFx, noFxPool)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContainingAny("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        Fixture noProxy = standard();
        Map<String, AssetSeries> noProxyPool = pool("A", noProxy.times("1.2"));
        noProxy.kr.remove(WINDOW_START.plusMonths(3));
        assertThatThrownBy(() -> judge(noProxy, noProxyPool)).isInstanceOf(IllegalStateException.class);

        Fixture outside = standard();
        Map<String, AssetSeries> outsidePool = pool("A", outside.times("1.2"));
        outside.fx.remove(YearMonth.of(2022, 11));
        outside.fx.remove(YearMonth.of(2026, 1));
        outside.kr.remove(YearMonth.of(2022, 10));
        assertThatCode(() -> judge(outside, outsidePool)).doesNotThrowAnyException();
    }

    @Test
    void P_N2_창_안에_월_블록이_없으면_모든_종목이_MONTHS이고_널_입력은_거부한다() {
        Fixture f = new Fixture(YearMonth.of(2020, 1), YearMonth.of(2021, 12));
        Map<String, BaseDateJudgement> got = judge(f, pool("A", f.times("1.2"), "B", f.times("0.5")));
        assertThat(got.values()).allSatisfy(j -> {
            assertThat(j.judgeable()).isFalse();
            assertThat(j.reason()).isEqualTo(PositionMeter.Reason.MONTHS);
        });

        Fixture g = standard();
        assertThatThrownBy(() -> BaseDatePositionCalculator.judge(null, pool(), g.history())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BaseDatePositionCalculator.judge(BASE, null, g.history())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BaseDatePositionCalculator.judge(BASE, pool(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssetSeries(null, new TreeMap<>())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void P_N1b_종목_자신의_달에_환율이_없으면_시장이_환율을_보지_않아도_그_종목_경로에서_멈춘다() {
        Fixture f = standard();
        YearMonth gap = WINDOW_START.plusMonths(7);
        AssetSeries krw = f.times("1.2");
        AssetSeries usdWithGap = f.asset(Currency.USD, m -> true, m -> d("0.01000000"));
        AssetSeries usdWithoutThatMonth = f.asset(Currency.USD, m -> !m.equals(gap), m -> d("0.01000000"));
        f.fx.remove(gap);
        // The US proxy is typed KRW, so the market series never reads an FX change; only a USD asset with a return in the gap month does.
        PositionHistory history = new PositionHistory(new TreeSet<>(f.blocks), Map.of(Currency.USD, new HashMap<>(f.fx)),
                new AssetSeries(Currency.KRW, new TreeMap<>(f.kr)), new AssetSeries(Currency.KRW, new TreeMap<>(f.us)));

        assertThatCode(() -> BaseDatePositionCalculator.judge(BASE, pool("KRW", krw, "USD_OTHER", usdWithoutThatMonth), history))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> BaseDatePositionCalculator.judge(BASE, pool("USD", usdWithGap), history))
                .isInstanceOf(IllegalStateException.class).hasMessage("USD return month has no FX change");
    }

    @Test
    void P_N3_월_블록이_역순_집합으로_들어와도_자연순으로_바로잡아_같은_결과를_낸다() {
        Fixture f = standard();
        Map<String, AssetSeries> pool = pool("A", f.times("1.2"), "B", f.times("0.5"), "C", f.times("2.0"));
        Map<String, BaseDateJudgement> ascending = judge(f, pool);

        PositionHistory reversed = new PositionHistory(f.blocks.descendingSet(), Map.of(Currency.USD, new HashMap<>(f.fx)),
                new AssetSeries(Currency.KRW, new TreeMap<>(f.kr)), new AssetSeries(Currency.USD, new TreeMap<>(f.us)));

        assertThat(reversed.monthBlocks().comparator()).as("natural order").isNull();
        assertThat(reversed.monthBlocks().first()).isEqualTo(f.blocks.first());
        assertThat(BaseDatePositionCalculator.judge(BASE, pool, reversed)).isEqualTo(ascending);

        NavigableMap<YearMonth, BigDecimal> reversedReturns = new TreeMap<>(java.util.Comparator.<YearMonth>reverseOrder());
        reversedReturns.putAll(pool.get("A").localReturns());
        assertThat(new AssetSeries(Currency.KRW, reversedReturns).localReturns().firstKey()).isEqualTo(f.blocks.first());
    }

    @Test
    void P_N4_입력을_복사하므로_만든_뒤_원본을_바꿔도_결과가_변하지_않고_접근자와_결과는_수정할_수_없다() {
        Fixture f = standard();
        NavigableMap<YearMonth, BigDecimal> returns = new TreeMap<>(f.times("1.2").localReturns());
        AssetSeries series = new AssetSeries(Currency.KRW, returns);
        Map<YearMonth, BigDecimal> usdFx = new HashMap<>(f.fx);
        Map<Currency, Map<YearMonth, BigDecimal>> fxChange = new HashMap<>(Map.of(Currency.USD, usdFx));
        NavigableSet<YearMonth> blocks = new TreeSet<>(f.blocks);
        NavigableMap<YearMonth, BigDecimal> kr = new TreeMap<>(f.kr);
        PositionHistory history = new PositionHistory(blocks, fxChange, new AssetSeries(Currency.KRW, kr),
                new AssetSeries(Currency.USD, new TreeMap<>(f.us)));
        Map<String, AssetSeries> pool = pool("A", series, "B", f.times("0.5"), "C", f.times("2.0"));
        Map<String, BaseDateJudgement> before = BaseDatePositionCalculator.judge(BASE, pool, history);

        returns.put(WINDOW_END, d("-0.90000000"));
        usdFx.put(WINDOW_START, d("0.50000000"));
        fxChange.clear();
        blocks.clear();
        kr.put(WINDOW_START, d("0.90000000"));

        assertThat(BaseDatePositionCalculator.judge(BASE, pool, history)).isEqualTo(before);
        assertThatThrownBy(() -> series.localReturns().put(WINDOW_END, BigDecimal.ONE)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> history.monthBlocks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> history.fxChange().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> history.fxChange().get(Currency.USD).put(WINDOW_START, BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> history.krProxy().localReturns().clear()).isInstanceOf(UnsupportedOperationException.class);

        BaseDatePositionCalculator.Detailed<String> detailed = BaseDatePositionCalculator.judgeDetailed(BASE, pool, history);
        assertThatThrownBy(() -> detailed.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.put("Z", before.get("A"))).isInstanceOf(UnsupportedOperationException.class);
        Map<String, BaseDateJudgement> mine = new LinkedHashMap<>(before);
        BaseDatePositionCalculator.Detailed<String> copied = new BaseDatePositionCalculator.Detailed<>(mine, detailed.marketMaxDrawdown());
        mine.clear();
        assertThat(copied.rows()).hasSize(3);
    }

    @Test
    void P_U7_하위_20퍼센트_경계에_걸친_동률_저변동성_종목은_모두_골키퍼다() {
        Fixture f = standard();
        // N = 10 so k = 2: the lowest volatility is first, the identical pair shares the second place (both inside the bottom 20 %)
        AssetSeries twin = f.times("0.15");
        Map<String, AssetSeries> pool = pool("LOW", f.times("0.1"), "TWIN_A", twin, "TWIN_B", twin,
                "M1", f.times("1.2"), "M2", f.times("1.5"), "M3", f.times("2.0"), "M4", f.times("2.5"),
                "M5", f.times("3.0"), "M6", f.times("3.5"), "M7", f.times("4.0"));
        Map<String, BaseDateJudgement> got = judge(f, pool);

        assertThat(got.get("TWIN_A").volatility()).isEqualByComparingTo(got.get("TWIN_B").volatility());
        assertThat(got.get("TWIN_A").downParticipation()).isLessThanOrEqualTo(d("0.2"));
        assertThat(got.get("LOW").systemPosition()).isEqualTo(Position.GOALKEEPER);
        assertThat(got.get("TWIN_A").systemPosition()).isEqualTo(Position.GOALKEEPER);
        assertThat(got.get("TWIN_B").systemPosition()).isEqualTo(Position.GOALKEEPER);
        assertThat(got.get("M1").systemPosition()).isNotEqualTo(Position.GOALKEEPER);
    }

    @Test
    void 판정_열은_전부_있거나_전부_없어야_한다() {
        assertThatThrownBy(() -> new BaseDateJudgement(PositionMeter.Reason.MONTHS, YearMonth.of(2025, 1), null, null, null, null, null,
                null, null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BaseDateJudgement(null, YearMonth.of(2025, 1), null, null, null, null, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
