package antlab.engine;

import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 시험용 합성 월 목록 — 번호는 달과 무관한 값(1000부터)이다. 실제 표본이 아니다. */
final class SyntheticMonths {

    static final LocalDate BASE_DATE = LocalDate.of(2026, 1, 2);

    private SyntheticMonths() {
    }

    /** 2006-12(범위 밖) + 2007-01–2012-04 + 2023-01–2026-01(상한 밖). */
    static List<LocalDate> sampleShape() {
        List<LocalDate> result = new ArrayList<>();
        result.add(LocalDate.of(2006, 12, 1));
        add(result, LocalDate.of(2007, 1, 1), LocalDate.of(2012, 4, 1));
        add(result, LocalDate.of(2023, 1, 1), LocalDate.of(2026, 1, 1));
        return result;
    }

    static void add(List<LocalDate> into, LocalDate fromInclusive, LocalDate toInclusive) {
        for (LocalDate d = fromInclusive; !d.isAfter(toInclusive); d = d.plusMonths(1)) {
            into.add(d);
        }
    }

    static List<ReplayMonth> withIds(List<LocalDate> months) {
        List<ReplayMonth> result = new ArrayList<>();
        long id = 1000;
        for (LocalDate m : months) {
            result.add(new ReplayMonth(id++, m));
        }
        return result;
    }

    static List<ReplayMonth> sample() {
        return withIds(sampleShape());
    }
}
