package antlab.agent.memory;

import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentProfileRepository;
import antlab.agent.ReflectionOutcome;
import antlab.agent.SeasonAgentConfig;
import antlab.agent.SeasonAgentConfigRepository;
import antlab.agent.decision.CallRecordChecks;
import antlab.agent.decision.CallRecordRequest;
import antlab.agent.decision.SchemaViolation;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write path of an agent's memory: one season's step becomes one outcome row and, when accepted, one new memory
 * version, in one transaction under a row lock on the agent. In order: the shape of the request (400), the lock, the gates
 * of {@link MemoryEligibility} (404/409), the stored calls (400), the base version (409), the JSON schemas (422), the leak
 * filter (422), then the inserts. Every failure leaves nothing behind. The schema check comes first because the filter skips
 * what is not a string. A refusal names pointers and fixed codes, never the text.
 */
@Service
public class MemorySubmitService {

    private static final int MAX_LEAKS = 50;

    private final CurrentMember currentMember;
    private final AgentProfileRepository profiles;
    private final MemoryEligibility eligibility;
    private final SeasonAgentConfigRepository configs;
    private final AgentMemoryRepository memories;
    private final AgentReflectionRepository reflections;
    private final AgentCallRepository calls;
    private final MemoryLeakFilters filters;
    private final JsonMapper mapper;

    public MemorySubmitService(CurrentMember currentMember, AgentProfileRepository profiles, MemoryEligibility eligibility,
            SeasonAgentConfigRepository configs, AgentMemoryRepository memories, AgentReflectionRepository reflections,
            AgentCallRepository calls, MemoryLeakFilters filters, JsonMapper mapper) {
        this.currentMember = currentMember;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.configs = configs;
        this.memories = memories;
        this.reflections = reflections;
        this.calls = calls;
        this.filters = filters;
        this.mapper = mapper;
    }

    @Transactional
    public SubmitSeasonMemoryResultDto submit(long agentId, long seasonId, SubmitSeasonMemoryRequest request) {
        requireActing(agentId);
        checkEnvelope(request);
        profiles.findByIdForUpdate(agentId).orElseThrow(MemorySubmitService::notFound);
        Season season = eligibility.requireReflectable(agentId, seasonId);
        SeasonAgentConfig config = configs.findById(seasonId).orElseThrow(MemorySubmitService::notFound);
        checkCalls(request, config);

        if (request.outcome() == ReflectionOutcome.DISCARDED) {
            storeCalls(seasonId, season.getPathLength(), request.calls());
            reflections.save(AgentReflection.discarded(seasonId, agentId, discardCodesJson(request.discardCodes()),
                    filters.version()));
            return new SubmitSeasonMemoryResultDto(ReflectionOutcome.DISCARDED, null);
        }

        Optional<AgentMemory> latest = memories.findFirstByMemberIdOrderByVersionDesc(agentId);
        Integer latestVersion = latest.map(AgentMemory::getVersion).orElse(null);
        if (!Objects.equals(request.baseVersion(), latestVersion)) {
            throw new DomainException(ErrorCode.MEMORY_BASE_STALE, "기준으로 삼은 메모리 버전이 최신이 아니다");
        }
        requireShape(request);
        JsonNode reflection = MemoryDocuments.nfc(request.reflection());
        JsonNode playbook = MemoryDocuments.nfc(request.playbook());
        MemoryLeakFilter filter = filters.current();
        requireClean(filter, reflection, playbook);
        requireCarriedPlaybook(request.carried(), reflection, playbook, latest);

        storeCalls(seasonId, season.getPathLength(), request.calls());
        reflections.save(AgentReflection.accepted(seasonId, agentId, reflection.toString(), filter.terms().version()));
        int version = (latestVersion == null ? 0 : latestVersion) + 1;
        memories.save(new AgentMemory(agentId, version, seasonId, playbook.toString(), request.carried(),
                filter.terms().version()));
        return new SubmitSeasonMemoryResultDto(ReflectionOutcome.ACCEPTED, version);
    }

    /** Rules that need no database. */
    private static void checkEnvelope(SubmitSeasonMemoryRequest request) {
        boolean accepted = request.outcome() == ReflectionOutcome.ACCEPTED;
        boolean hasCodes = request.discardCodes() != null && !request.discardCodes().isEmpty();
        if (accepted) {
            if (request.reflection() == null || request.reflection().isNull() || request.playbook() == null
                    || request.playbook().isNull()) {
                throw invalid("ACCEPTED 에는 복기와 운용 노트가 필요하다");
            }
            if (hasCodes) {
                throw invalid("ACCEPTED 에는 폐기 사유를 보내지 않는다");
            }
        } else {
            if (!hasCodes) {
                throw invalid("DISCARDED 에는 폐기 사유가 필요하다");
            }
            if (new HashSet<>(request.discardCodes()).size() != request.discardCodes().size()) {
                throw invalid("폐기 사유가 겹친다");
            }
            if ((request.reflection() != null && !request.reflection().isNull())
                    || (request.playbook() != null && !request.playbook().isNull()) || request.carried()) {
                throw invalid("DISCARDED 에는 복기나 운용 노트를 보내지 않는다");
            }
            if (request.baseVersion() != null) {
                throw invalid("DISCARDED 에는 기준 버전을 보내지 않는다");
            }
        }
        if (request.calls().stream().anyMatch(c -> c.kind() == AgentCallKind.TURN_DECISION)) {
            throw invalid("기억 호출은 REFLECTION 또는 PLAYBOOK 종류여야 한다");
        }
        if (accepted && request.calls().stream().noneMatch(c -> c.kind() == AgentCallKind.REFLECTION)) {
            throw invalid("ACCEPTED 에는 복기 호출 기록이 필요하다");
        }
    }

    /** Per-call rules; attempts are numbered 1, 2, … within each kind. */
    private void checkCalls(SubmitSeasonMemoryRequest request, SeasonAgentConfig config) {
        List<CallRecordRequest> records = request.calls();
        CallRecordChecks.requireDistinctLedgerIds(records);
        Map<AgentCallKind, Integer> seen = new EnumMap<>(AgentCallKind.class);
        for (CallRecordRequest c : records) {
            int expected = seen.merge(c.kind(), 1, Integer::sum);
            if (c.attemptNo() != expected) {
                throw invalid("시도 번호가 종류마다 1부터 이어지지 않는다");
            }
            CallRecordChecks.checkRecord(c, config);
        }
        CallRecordChecks.requireUnusedLedgerIds(records, calls);
    }

    private void requireShape(SubmitSeasonMemoryRequest request) {
        List<SchemaViolation> all = new ArrayList<>();
        for (SchemaViolation v : MemoryDocuments.checkReflection(request.reflection())) {
            all.add(new SchemaViolation("/reflection" + v.pointer(), v.keyword()));
        }
        for (SchemaViolation v : MemoryDocuments.checkNote(request.playbook())) {
            all.add(new SchemaViolation("/playbook" + v.pointer(), v.keyword()));
        }
        if (!all.isEmpty()) {
            List<Map<String, String>> violations = all.stream()
                    .map(v -> member("pointer", v.pointer(), "keyword", v.keyword())).toList();
            throw new DomainException(ErrorCode.MEMORY_SCHEMA_INVALID, "복기나 운용 노트가 정해진 모양이 아니다",
                    Map.of("violations", violations));
        }
    }

    private static void requireClean(MemoryLeakFilter filter, JsonNode reflection, JsonNode playbook) {
        List<Map<String, String>> leaks = new ArrayList<>();
        collect(leaks, "/reflection", filter.checkReflection(reflection));
        collect(leaks, "/playbook", filter.checkNote(playbook));
        if (!leaks.isEmpty()) {
            throw new DomainException(ErrorCode.MEMORY_LEAK, "복기나 운용 노트가 누출 필터에 걸렸다", Map.of("leaks", leaks));
        }
    }

    private static void collect(List<Map<String, String>> out, String prefix, MemoryLeakFilter.Verdict verdict) {
        for (MemoryLeakFilter.Hit hit : verdict.hits()) {
            if (out.size() < MAX_LEAKS) {
                out.add(member("pointer", prefix + hit.pointer(), "code", hit.code().name()));
            }
        }
        if (verdict.oversize() && out.size() < MAX_LEAKS) {
            out.add(member("pointer", prefix, "code", MemoryDiscardCode.OVERSIZE.name()));
        }
    }

    /**
     * A carried playbook is the previous one unchanged; with no previous version it is the reflection's rule candidates.
     * Otherwise the flag would say a rewrite failed when it did not.
     */
    private void requireCarriedPlaybook(boolean carried, JsonNode reflection, JsonNode playbook, Optional<AgentMemory> latest) {
        if (!carried) {
            return;
        }
        JsonNode expected = latest.isPresent() ? mapper.readTree(latest.get().getPlaybook()).path("rules")
                : reflection.path("ruleCandidates");
        if (!expected.equals(playbook.path("rules"))) {
            throw invalid("이월한 운용 노트가 이월할 규칙과 다르다");
        }
    }

    private void storeCalls(long seasonId, int pathLength, List<CallRecordRequest> records) {
        for (CallRecordRequest c : records) {
            calls.save(new AgentCall(seasonId, pathLength, c.attemptNo(), c.kind(), c.ledgerCallId(), c.status(),
                    c.httpStatus(), c.modelRequested(), c.modelActual(), c.provider(), c.requestText(), c.responseText(),
                    null, c.tokensIn(), c.tokensOut(), c.costUsd(), c.latencyMs(), null, null, null));
        }
    }

    private String discardCodesJson(List<MemoryDiscardCode> codes) {
        return mapper.valueToTree(codes.stream().map(Enum::name).toList()).toString();
    }

    /** Two members in the order written, so the answer reads the same on every run. */
    private static Map<String, String> member(String firstKey, String first, String secondKey, String second) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(firstKey, first);
        out.put(secondKey, second);
        return out;
    }

    private void requireActing(long agentId) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
