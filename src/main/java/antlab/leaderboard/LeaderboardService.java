package antlab.leaderboard;

import antlab.agent.AgentCallRepository;
import antlab.agent.AgentFailedTurnRow;
import antlab.agent.detection.DetectionReader;
import antlab.agent.detection.DetectionResult;
import antlab.common.money.MoneyMath;
import antlab.engine.client.EngineManagerPartsRequest;
import antlab.member.CurrentMember;
import antlab.member.MemberKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager leaderboard (AG-06 G2, PLAN §6.9). S is computed per counted season from its stored parts and the current
 * formula, L over the newest 10 counted seasons ({@link CareerScore}); members ranked once they reach the minimum of
 * counted seasons (3 humans, 5 agents). Detection, multiple and failed turns are shown only: they never enter the order,
 * the filter or the minimum. Every read runs in one read-only {@code REPEATABLE_READ} snapshot.
 */
@Service
public class LeaderboardService {

    static final int HUMAN_MINIMUM = 3;
    static final int AGENT_MINIMUM = 5;

    private static final Logger LOG = LoggerFactory.getLogger(LeaderboardService.class);
    private static final String NAME_JOIN = " · ";

    private final CurrentMember currentMember;
    private final LeaderboardQuery query;
    private final ManagerScoreFormulaReader formulas;
    private final ManagerPartsQuery partsQueue;
    private final AgentCallRepository calls;
    private final DetectionReader detection;

    public LeaderboardService(CurrentMember currentMember, LeaderboardQuery query, ManagerScoreFormulaReader formulas,
            ManagerPartsQuery partsQueue, AgentCallRepository calls, DetectionReader detection) {
        this.currentMember = currentMember;
        this.query = query;
        this.formulas = formulas;
        this.partsQueue = partsQueue;
        this.calls = calls;
        this.detection = detection;
    }

    static int minimum(MemberKind kind) {
        return kind == MemberKind.AGENT ? AGENT_MINIMUM : HUMAN_MINIMUM;
    }

    /**
     * A member with their career score; {@code career} is null below the minimum. {@code displayName} is resolved before
     * any sorting; it is null for an agent whose newest used season has no persona pin.
     */
    private record Scored(LeaderboardQuery.MemberBoard board, CareerScore.Result career, BigDecimal meanMultiple,
            String displayName) {

        boolean ranked() {
            return career != null;
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ManagerLeaderboardDto get(LeaderboardKind kind) {
        long me = currentMember.existingId().orElseThrow(() -> new IllegalStateException("leaderboard.no_member"));
        String version = EngineManagerPartsRequest.REFERENCE_VERSION;
        ManagerScoreFormula formula = formulas.current()
                .orElseThrow(() -> new IllegalStateException("leaderboard.no_formula"));
        int pending = partsQueue.pendingCount(version);

        List<Scored> scored = query.board(version).stream().map(b -> score(b, formula)).toList();
        long unnamed = scored.stream().filter(s -> s.ranked() && s.displayName() == null).count();
        if (unnamed > 0) {
            // an agent season always pins its persona at start; one broken row must not take the whole board down
            LOG.error("leaderboard: {} ranked agent row(s) left out, newest used season has no persona pin", unnamed);
        }
        List<Scored> listed = scored.stream().filter(s -> s.ranked() && s.displayName() != null)
                .filter(s -> kind == LeaderboardKind.ALL || s.board().kind().name().equals(kind.name()))
                .sorted(Comparator.comparing((Scored s) -> s.career().l()).reversed()
                        .thenComparing(s -> s.career().m(), Comparator.reverseOrder())
                        .thenComparing(Scored::displayName)
                        .thenComparing(s -> s.board().memberId()))
                .toList();

        List<Long> agentIds = listed.stream().filter(s -> s.board().kind() == MemberKind.AGENT)
                .map(s -> s.board().memberId()).toList();
        Map<Long, Long> failed = agentIds.isEmpty() ? Map.of()
                : calls.findFailedTurnCounts(agentIds).stream()
                        .collect(Collectors.toMap(AgentFailedTurnRow::agentId, AgentFailedTurnRow::failedTurns));
        Map<String, DetectionResult> detections = detection.forModels(listed.stream()
                .filter(s -> s.board().kind() == MemberKind.AGENT).map(s -> s.board().modelId())
                .collect(Collectors.toCollection(LinkedHashSet::new)));

        Map<Long, Integer> ranks = new HashMap<>();
        List<ManagerLeaderboardDto.Row> rows = new ArrayList<>();
        for (Scored s : listed) {
            int rank = rank(s.career().l(), listed);
            ranks.put(s.board().memberId(), rank);
            rows.add(row(s, rank, me, failed, detections));
        }
        TreeSet<String> processVersions = listed.stream().flatMap(s -> s.board().used().stream())
                .map(u -> u.parts().processScoreVersion()).collect(Collectors.toCollection(TreeSet::new));

        int humans = 0;
        int agents = 0;
        for (Scored s : scored) {
            if (!s.ranked() && s.board().counts().counted() > 0) {
                if (s.board().kind() == MemberKind.AGENT) {
                    agents++;
                } else {
                    humans++;
                }
            }
        }
        ManagerLeaderboardDto.Me mine = scored.stream().filter(s -> s.board().memberId() == me).findFirst()
                .map(s -> me(s, ranks.get(me), version, formula)).orElse(null);
        return new ManagerLeaderboardDto(formula(formula), version, List.copyOf(processVersions), kind, pending, rows,
                new ManagerLeaderboardDto.Unranked(humans, agents), mine);
    }

    private static Scored score(LeaderboardQuery.MemberBoard b, ManagerScoreFormula formula) {
        if (b.counts().counted() < minimum(b.kind())) {
            return new Scored(b, null, null, null);
        }
        List<BigDecimal> s = b.used().stream().map(u -> s(formula, u.parts())).toList();
        List<BigDecimal> multiples = b.used().stream().map(u -> u.parts().finalMultiple()).toList();
        return new Scored(b, CareerScore.of(s), MoneyMath.mean(multiples, 8), displayName(b));
    }

    private static BigDecimal s(ManagerScoreFormula formula, LeaderboardQuery.Parts p) {
        return CareerScore.seasonScore(formula, p.partReturn(), p.partRiskAdjusted(), p.partRisk(), p.partProcess());
    }

    /** One plus the number of listed rows with a strictly higher rounded L, so ties share the better rank. */
    private static int rank(BigDecimal l, List<Scored> listed) {
        int ahead = 0;
        for (Scored other : listed) {
            if (other.career().l().compareTo(l) > 0) {
                ahead++;
            }
        }
        return ahead + 1;
    }

    /** A human's name; for an agent the persona name of the newest used season and the model id, null without a pin. */
    private static String displayName(LeaderboardQuery.MemberBoard b) {
        if (b.kind() == MemberKind.HUMAN) {
            return b.name();
        }
        LeaderboardQuery.UsedSeason newest = b.used().get(0);
        if (newest.personaVersion() == null || newest.personaName() == null) {
            return null;
        }
        return newest.personaName() + NAME_JOIN + b.modelId();
    }

    private static ManagerLeaderboardDto.Row row(Scored s, int rank, long me, Map<Long, Long> failed,
            Map<String, DetectionResult> detections) {
        LeaderboardQuery.MemberBoard b = s.board();
        CareerScore.Result c = s.career();
        ManagerLeaderboardDto.Agent agent = null;
        if (b.kind() == MemberKind.AGENT) {
            LeaderboardQuery.UsedSeason newest = b.used().get(0);
            List<Integer> versions = b.used().stream().map(LeaderboardQuery.UsedSeason::personaVersion)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            agent = new ManagerLeaderboardDto.Agent(newest.personaName(), newest.personaVersion(), versions,
                    newest.memoryVersion(), b.modelId(), Math.toIntExact(failed.getOrDefault(b.memberId(), 0L)),
                    detection(detections.get(b.modelId())), null);
        }
        boolean owned = b.ownerMemberId() != null && b.ownerMemberId() == me;
        return new ManagerLeaderboardDto.Row(rank, b.kind(), s.displayName(), b.memberId() == me, owned, c.l(),
                MoneyMath.round2(c.trimmedMean()), MoneyMath.round2(c.sampleSd()), c.m(), s.meanMultiple(), b.counts(),
                agent);
    }

    private static ManagerLeaderboardDto.Detection detection(DetectionResult result) {
        if (result instanceof DetectionResult.Tested t) {
            List<ManagerLeaderboardDto.Window> windows = t.windows().stream()
                    .map(w -> new ManagerLeaderboardDto.Window(w.months(), w.units(), w.runAt(), w.metrics().stream()
                            .map(m -> new ManagerLeaderboardDto.Metric(m.metric(), m.accuracy(), m.baseRate())).toList()))
                    .toList();
            return new ManagerLeaderboardDto.Detection(t.status(), t.testVersion(), t.sampleLayer(), t.runAt(), windows);
        }
        DetectionResult notTested = Objects.requireNonNull(result, "detection");
        return new ManagerLeaderboardDto.Detection(notTested.status(), null, null, null, null);
    }

    private ManagerLeaderboardDto.Me me(Scored s, Integer rank, String version, ManagerScoreFormula formula) {
        LeaderboardQuery.MemberBoard b = s.board();
        List<ManagerLeaderboardDto.MySeason> seasons = query.mine(version, b.memberId()).stream().map(m -> {
            LeaderboardQuery.Parts p = m.parts();
            if (p == null) {
                return new ManagerLeaderboardDto.MySeason(m.seasonNo(), m.excluded(), m.used(), null, null, null, null);
            }
            return new ManagerLeaderboardDto.MySeason(m.seasonNo(), null, m.used(), MoneyMath.round2(s(formula, p)),
                    new ManagerLeaderboardDto.Parts(p.partReturn(), p.partRiskAdjusted(), p.partRisk(), p.partProcess()),
                    p.processScoreVersion(), p.finalMultiple());
        }).toList();
        return new ManagerLeaderboardDto.Me(b.kind(), b.counts().counted(), minimum(b.kind()), s.ranked(), rank,
                s.ranked() ? s.career().l() : null, b.counts(), seasons);
    }

    private static ManagerLeaderboardDto.Formula formula(ManagerScoreFormula f) {
        return new ManagerLeaderboardDto.Formula(f.version(), f.status(),
                new ManagerLeaderboardDto.Weights(f.wReturn(), f.wRiskAdjusted(), f.wRisk(), f.wProcess()));
    }
}
