package antlab.engine;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 단순 부트스트랩 경로 생성기(engine_version "p0-boot6-v1"). 순수 함수 — DB·설정·시계를 읽지 않는다.
 * 6개월 블록을 1월·7월 시작 후보에서 시드로 골라 이어 붙인다(PLAN §4.2 의 정상 부트스트랩은 M2 몫).
 * 아래 상수·난수 식·후보 규칙이 하나라도 바뀌면 같은 시드가 다른 경로를 내므로 ENGINE_VERSION 을 올린다.
 */
public final class SimpleBootstrap {

    /** 블록 길이(개월). */
    public static final int BLOCK_LENGTH = 6;
    /** 허용 경로 길이(턴). */
    public static final List<Integer> ALLOWED_LENGTHS = List.of(12, 24, 60);
    /** 허용 길이의 최댓값. 이보다 길면 PATH_TOO_LONG. */
    public static final int MAX_LENGTH = 60;
    /** 재생 시작(erd S1, Phase 0). */
    public static final LocalDate REPLAY_START = LocalDate.of(2007, 1, 1);
    /** season.engine_version 에 저장하는 값(VARCHAR(20)). */
    public static final String ENGINE_VERSION = "p0-boot6-v1";

    private static final String TOO_LONG_MESSAGE = "경로 길이 %d턴은 만들 수 없다(가능: 12·24·60)";
    private static final String NO_CAPACITY_MESSAGE = "지금 재생 구간으로는 경로 길이 %d턴을 만들 수 없다";

    private SimpleBootstrap() {
    }

    /** 재생 범위의 상한(제외) = 기준일이 속한 해의 1월 1일(erd S1). */
    public static LocalDate replayUpperBound(LocalDate baseDate) {
        return LocalDate.of(Objects.requireNonNull(baseDate, "baseDate").getYear(), 1, 1);
    }

    /** 턴 순서의 month_block 번호 목록으로 경로를 만든다. 결과는 달(month_start) 기준으로 재현된다. */
    public static GeneratedPath generate(List<ReplayMonth> months, PathRequest request) {
        Objects.requireNonNull(months, "months");
        Objects.requireNonNull(request, "request");
        TreeMap<YearMonth, Long> idByMonth = present(months, request.baseDate());
        List<YearMonth> picked = pickMonths(idByMonth, request);
        List<Long> ids = new ArrayList<>(picked.size());
        for (YearMonth ym : picked) {
            ids.add(idByMonth.get(ym));
        }
        return new GeneratedPath(ids, ENGINE_VERSION);
    }

    /** 턴 순서의 달 목록(각 달의 1일). 패키지 안 시험 전용 — 달은 [복기 전용]이라 엔진 밖으로 내지 않는다. */
    static List<LocalDate> generateMonths(List<ReplayMonth> months, PathRequest request) {
        Objects.requireNonNull(months, "months");
        Objects.requireNonNull(request, "request");
        List<LocalDate> result = new ArrayList<>();
        for (YearMonth ym : pickMonths(present(months, request.baseDate()), request)) {
            result.add(ym.atDay(1));
        }
        return result;
    }

    /** 재생 범위 안의 달만 모아 달 오름차순으로 정렬한다. 입력 순서·번호와 무관하다. */
    private static TreeMap<YearMonth, Long> present(List<ReplayMonth> months, LocalDate baseDate) {
        LocalDate upper = replayUpperBound(baseDate);
        TreeMap<YearMonth, Long> result = new TreeMap<>();
        for (ReplayMonth m : months) {
            LocalDate start = Objects.requireNonNull(m, "month").monthStart();
            if (start.getDayOfMonth() != 1) {
                throw new IllegalArgumentException("월 목록 오류: 1일이 아닌 달이 있다");
            }
            if (start.isBefore(REPLAY_START) || !start.isBefore(upper)) {
                continue;
            }
            if (result.put(YearMonth.from(start), Objects.requireNonNull(m.monthBlockId(), "monthBlockId")) != null) {
                throw new IllegalArgumentException("월 목록 오류: 같은 달이 두 번 있다");
            }
        }
        return result;
    }

    private static List<YearMonth> pickMonths(TreeMap<YearMonth, Long> present, PathRequest request) {
        int length = request.length();
        if (length > MAX_LENGTH) {
            throw new DomainException(ErrorCode.PATH_TOO_LONG, TOO_LONG_MESSAGE.formatted(length));
        }
        if (!ALLOWED_LENGTHS.contains(length)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "경로 길이는 12·24·60 중 하나다");
        }

        // 후보 블록 시작 달: 시작 달부터 6개월이 모두 있는 달(끊긴 곳을 넘는 블록은 저절로 빠진다)
        List<YearMonth> candidates = new ArrayList<>();
        for (Map.Entry<YearMonth, Long> e : present.entrySet()) {
            YearMonth s = e.getKey();
            boolean whole = true;
            for (int i = 1; i < BLOCK_LENGTH && whole; i++) {
                whole = present.containsKey(s.plusMonths(i));
            }
            if (whole) {
                candidates.add(s);
            }
        }

        SplitMix64 rng = new SplitMix64(request.seed());
        Set<YearMonth> used = new HashSet<>();
        YearMonth prevEnd = null;
        List<YearMonth> path = new ArrayList<>(length);
        for (int slot = 0; slot < length / BLOCK_LENGTH; slot++) {
            int calendarMonth = ((slot * BLOCK_LENGTH) % 12) + 1; // 1월, 7월, 1월, … (S4)
            List<YearMonth> choices = new ArrayList<>();
            for (YearMonth s : candidates) {
                if (s.getMonthValue() == calendarMonth && !overlaps(s, used)
                        && (prevEnd == null || !s.equals(prevEnd.plusMonths(1)))) {
                    choices.add(s);
                }
            }
            if (choices.isEmpty()) {
                throw new DomainException(ErrorCode.PATH_TOO_LONG, NO_CAPACITY_MESSAGE.formatted(length));
            }
            YearMonth pick = choices.get(rng.nextInt(choices.size()));
            for (int i = 0; i < BLOCK_LENGTH; i++) {
                YearMonth ym = pick.plusMonths(i);
                path.add(ym);
                used.add(ym);
            }
            prevEnd = pick.plusMonths(BLOCK_LENGTH - 1);
        }
        return path;
    }

    private static boolean overlaps(YearMonth start, Set<YearMonth> used) {
        for (int i = 0; i < BLOCK_LENGTH; i++) {
            if (used.contains(start.plusMonths(i))) {
                return true;
            }
        }
        return false;
    }
}
