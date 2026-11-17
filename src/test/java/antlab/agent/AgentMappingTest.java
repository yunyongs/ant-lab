package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.Member;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.support.JpaMappingTest;
import antlab.support.SeasonAccountFixture;
import antlab.account.CashAccountRepository;
import antlab.season.SeasonRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Entities of the agent schema against the real tables: insert path, defaults, JSON text and append-only reads. */
@JpaMappingTest
class AgentMappingTest {

    private static final String SHA = "b".repeat(64);

    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;
    @Autowired
    AgentProfileRepository profiles;
    @Autowired
    SeasonAgentConfigRepository configs;
    @Autowired
    AgentCallRepository calls;

    private Member owner() {
        return new SeasonAccountFixture(jdbc, members, seasons, accounts).newMember();
    }

    private AgentProfile newAgent(Member owner) {
        Member agent = members.save(Member.agent("agent-1"));
        em.flush();
        return profiles.save(new AgentProfile(agent.getId(), owner.getId(), "calm-value", ModelRoute.OPENROUTER,
                "vendor/model-a"));
    }

    @Test
    void agentMemberHasKindAgentAndNoCredentials() {
        Member agent = members.save(Member.agent("agent-1"));
        em.flush();
        em.clear();

        Member loaded = members.findById(agent.getId()).orElseThrow();
        assertThat(loaded.getKind()).isEqualTo(MemberKind.AGENT);
        assertThat(loaded.getEmail()).isNull();
        assertThat(loaded.getPassword()).isNull();
        assertThat(loaded.getRole().name()).isEqualTo("USER");
    }

    @Test
    void humanMemberKeepsKindHuman() {
        Member human = owner();
        em.clear();

        assertThat(members.findById(human.getId()).orElseThrow().getKind()).isEqualTo(MemberKind.HUMAN);
    }

    @Test
    void profileInsertsFillsKindDefaultsAndRetires() {
        AgentProfile profile = newAgent(owner());
        em.flush();
        em.clear();

        AgentProfile loaded = profiles.findById(profile.getMemberId()).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getMemberKind()).isEqualTo("AGENT");
        assertThat(loaded.getOwnerKind()).isEqualTo("HUMAN");
        assertThat(loaded.getStatus()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(profiles.findByOwnerMemberIdAndPersonaKeyAndModelRouteAndModelId(loaded.getOwnerMemberId(),
                "calm-value", ModelRoute.OPENROUTER, "vendor/model-a")).isPresent();

        loaded.retire();
        em.flush();
        em.clear();
        assertThat(profiles.findById(profile.getMemberId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.RETIRED);
    }

    @Test
    void configAndCallRoundTripIncludingJsonAndMoney() {
        Member owner = owner();
        AgentProfile agent = newAgent(owner);
        Season season = new SeasonAccountFixture(jdbc, members, seasons, accounts)
                .newSeason(members.findById(agent.getMemberId()).orElseThrow());
        em.flush();
        configs.save(new SeasonAgentConfig(season.getId(), agent.getMemberId(), "calm-value", 1, SHA,
                ModelRoute.OPENROUTER, "vendor/model-a", new BigDecimal("0.200"), null, 2000, 60000, "p1", "r1",
                null, 100, new BigDecimal("1.50000000")));
        UUID ledgerId = UUID.randomUUID();
        AgentCall attempt = calls.save(new AgentCall(season.getId(), 0, 1, AgentCallKind.TURN_DECISION, ledgerId,
                AgentCallStatus.INVALID, 200, "vendor/model-a", "vendor/model-a-2", "prov", "req", "resp", null, 10,
                20, new BigDecimal("0.00001234"), 150, null, null, null));
        AgentCall last = calls.save(new AgentCall(season.getId(), 0, 2, AgentCallKind.TURN_DECISION, null,
                AgentCallStatus.OK, 200, "vendor/model-a", null, null, "req2", "resp2", "{\"schema\":\"turn-decision/1\"}",
                null, null, null, 90, DecisionOutcome.DECISION, "[{\"unit\":\"order\",\"index\":0,\"code\":\"X\"}]",
                SHA));
        em.flush();
        em.clear();

        SeasonAgentConfig config = configs.findById(season.getId()).orElseThrow();
        assertThat(config.isNew()).isFalse();
        assertThat(config.getBudgetUsd()).isEqualByComparingTo("1.5");
        assertThat(config.getTemperature()).isEqualByComparingTo("0.2");
        assertThat(config.getModelSeed()).isNull();
        AgentCall a = calls.findById(attempt.getId()).orElseThrow();
        assertThat(a.isFinalCall()).isFalse();
        assertThat(a.getLedgerCallId()).isEqualTo(ledgerId);
        assertThat(a.getCostUsd()).isEqualByComparingTo("0.00001234");
        AgentCall f = calls.findFinalBySeasonIdAndTurnNo(season.getId(), 0, AgentCallKind.TURN_DECISION).orElseThrow();
        assertThat(f.getId()).isEqualTo(last.getId());
        assertThat(f.isFinalCall()).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(parsed) FROM agent_call WHERE id = ?", String.class,
                f.getId())).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(rejects) FROM agent_call WHERE id = ?", String.class,
                f.getId())).isEqualTo("array");
        assertThat(calls.findAllBySeasonIdAndTurnNo(season.getId(), 0)).extracting(AgentCall::getAttemptNo)
                .containsExactly(1, 2);
    }
}
