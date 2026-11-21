package antlab.agent.memory;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of an agent's own memory. Every read is keyed by the acting agent: another agent's version or reflection cannot be
 * named through these methods.
 */
@Service
public class MemoryReadService {

    /** A version holds its own operating note and the reflections that made it and the two versions before. */
    static final int REFLECTIONS_PER_VERSION = 3;

    private final CurrentMember currentMember;
    private final AgentMemoryRepository memories;
    private final AgentReflectionRepository reflections;
    private final MemoryEligibility eligibility;
    private final JsonMapper mapper;

    public MemoryReadService(CurrentMember currentMember, AgentMemoryRepository memories,
            AgentReflectionRepository reflections, MemoryEligibility eligibility, JsonMapper mapper) {
        this.currentMember = currentMember;
        this.memories = memories;
        this.reflections = reflections;
        this.eligibility = eligibility;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public AgentMemoryStateDto state(long agentId) {
        requireActing(agentId);
        Integer latest = memories.findFirstByMemberIdOrderByVersionDesc(agentId).map(AgentMemory::getVersion).orElse(null);
        MemoryEligibility.Pending pending = eligibility.pending(agentId);
        return new AgentMemoryStateDto(latest, pending.released(), pending.held());
    }

    @Transactional(readOnly = true)
    public AgentMemoryVersionDto version(long agentId, int version) {
        requireActing(agentId);
        AgentMemory row = memories.findById(new AgentMemoryId(agentId, version)).orElseThrow(MemoryReadService::notFound);
        return new AgentMemoryVersionDto(memoryOf(agentId, row), row.isPlaybookCarried(), row.getCreatedAt());
    }

    /**
     * The memory a season pinned to {@code version} reads, or empty when the agent has no such version. No actor check:
     * the caller must pass the season's own agent id, never a path id.
     */
    @Transactional(readOnly = true)
    public Optional<AgentMemoryDto> memory(long agentId, int version) {
        return memories.findById(new AgentMemoryId(agentId, version)).map(row -> memoryOf(agentId, row));
    }

    /** The rules of an agent's latest operating note, empty without a version. */
    @Transactional(readOnly = true)
    public Optional<AgentMemory> latest(long agentId) {
        return memories.findFirstByMemberIdOrderByVersionDesc(agentId);
    }

    public List<String> rules(String playbookJson) {
        List<String> out = new ArrayList<>();
        for (JsonNode rule : mapper.readTree(playbookJson).path("rules")) {
            out.add(rule.asString());
        }
        return List.copyOf(out);
    }

    private AgentMemoryDto memoryOf(long agentId, AgentMemory row) {
        int from = Math.max(1, row.getVersion() - (REFLECTIONS_PER_VERSION - 1));
        List<JsonNode> bodies = new ArrayList<>();
        for (AgentMemory made : memories.findByMemberIdAndVersionBetweenOrderByVersionAsc(agentId, from, row.getVersion())) {
            AgentReflection reflection = reflections.findById(made.getSourceSeasonId()).orElseThrow(
                    () -> new IllegalStateException("agent_memory has no source reflection: version " + made.getVersion()));
            bodies.add(mapper.readTree(reflection.getBody()));
        }
        return new AgentMemoryDto(row.getVersion(), rules(row.getPlaybook()), List.copyOf(bodies));
    }

    private void requireActing(long agentId) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
