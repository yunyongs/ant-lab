package antlab.agent;

import antlab.account.HoldingsQueryService;
import antlab.account.OrderRepository;
import antlab.account.RecentTradeRow;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.asset.PoolService;
import antlab.member.CurrentMember;
import antlab.season.SeasonDto;
import antlab.season.SeasonQueryService;
import antlab.season.TurnQueryService;
import antlab.season.TurnResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the turn screen of an agent. Every embedded part comes from the service that answers the human turn endpoints
 * (the same code runs for the acting agent member), so the agent cannot be shown more than a human is. The agent part
 * adds only the agent's own past: its fills in the last {@code k} turns before the current one, and the rejects of the
 * previous turn's final call. Nothing of the current turn or later is read. One snapshot serves all parts.
 */
@Service
public class AgentTurnViewService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnViewService.class);

    static final Set<String> UNITS = Set.of("FORMATION", "ORDER", "THESES");
    static final String REPLY_CODE = "SCHEMA_INVALID";
    static final int MAX_ORDERS = 40;
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z_]{0,39}$");
    /** Up to six path steps of plain names or array positions; model-chosen keys with other characters do not pass. */
    private static final Pattern POINTER = Pattern.compile("^(/[A-Za-z0-9_]{1,40}){1,6}$");

    private final CurrentMember currentMember;
    private final SeasonQueryService seasonQueries;
    private final TurnQueryService turnQueries;
    private final HoldingsQueryService holdingsQueries;
    private final PoolService pools;
    private final OrderRepository orders;
    private final AgentCallRepository calls;
    private final int recentTurns;

    public AgentTurnViewService(CurrentMember currentMember, SeasonQueryService seasonQueries,
            TurnQueryService turnQueries, HoldingsQueryService holdingsQueries, PoolService pools,
            OrderRepository orders, AgentCallRepository calls,
            @Value("${antlab.agent-runner.recent-turns:3}") int recentTurns) {
        if (recentTurns < 1) {
            throw new IllegalArgumentException("antlab.agent-runner.recent-turns must be at least 1");
        }
        this.currentMember = currentMember;
        this.seasonQueries = seasonQueries;
        this.turnQueries = turnQueries;
        this.holdingsQueries = holdingsQueries;
        this.pools = pools;
        this.orders = orders;
        this.calls = calls;
        this.recentTurns = recentTurns;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentTurnViewDto get(long agentId, long seasonId) {
        // The filter already resolved the path's agent; a mismatch is the same "not found" as a season of someone else.
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
        SeasonDto season = seasonQueries.get(seasonId);
        int turn = season.currentTurnNo();
        TurnResultDto lastTurn = turn >= 1 ? turnQueries.get(seasonId, turn) : null;
        return new AgentTurnViewDto(season, lastTurn, holdingsQueries.get(seasonId), seasonQueries.prices(seasonId, null),
                pools.pool().assets(), new AgentPartDto(AgentPartDto.SCHEMA, recentTrades(seasonId, turn),
                        lastRejects(seasonId, turn), null));
    }

    /** Fills of the turns {@code turn - k .. turn - 1}; the current turn and later ones are never read. */
    private List<RecentTradeDto> recentTrades(long seasonId, int turn) {
        if (turn < 1) {
            return List.of();
        }
        List<RecentTradeRow> rows = orders.findTrades(seasonId, Math.max(0, turn - recentTurns), turn - 1);
        return rows.stream().map(r -> new RecentTradeDto(r.turnNo(), r.side(), r.market().name() + ":" + r.ticker(),
                r.quantity(), MoneyMath.exactWon(r.fillPrice()), MoneyMath.exactWon(r.fee()), r.origin())).toList();
    }

    /** Rejects the engine recorded on the previous turn's final decision call; none if there is no such list. */
    private List<RejectDto> lastRejects(long seasonId, int turn) {
        if (turn < 1) {
            return List.of();
        }
        return calls.findFinalRejects(seasonId, turn - 1, AgentCallKind.TURN_DECISION).map(stored -> {
            ParsedRejects parsed = parseRejects(stored);
            if (parsed.dropped() > 0) {
                // No stored value in the message: the text came from a reply.
                log.warn("agent_call.rejects has {} unusable item(s): seasonId={} turnNo={}", parsed.dropped(), seasonId,
                        turn - 1);
            }
            return parsed.items();
        }).orElse(List.of());
    }

    /** The usable items of a stored list and how many items (or whole lists) were left out. */
    record ParsedRejects(List<RejectDto> items, int dropped) {
    }

    /**
     * Reads the stored reject list. The engine writes it, but a row of another shape must not stop the turn: items that
     * do not fit the vocabulary are left out (and counted), and a list that cannot be read at all gives no items.
     * A unit reject: unit in {@link #UNITS}; index 0-39 for ORDER, null otherwise; no pointer. A reply-level reject:
     * no unit, no index, code {@value #REPLY_CODE}, and a pointer of plain names.
     */
    static ParsedRejects parseRejects(String json) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(json);
        } catch (RuntimeException e) {
            return new ParsedRejects(List.of(), 1);
        }
        if (root == null || !root.isArray()) {
            return new ParsedRejects(List.of(), 1);
        }
        List<RejectDto> out = new ArrayList<>();
        int dropped = 0;
        for (JsonNode item : root) {
            RejectDto reject = readReject(item);
            if (reject == null) {
                dropped++;
            } else {
                out.add(reject);
            }
        }
        return new ParsedRejects(List.copyOf(out), dropped);
    }

    private static RejectDto readReject(JsonNode item) {
        if (!item.isObject()) {
            return null;
        }
        String code = text(item.get("code"));
        if (code == null || !CODE.matcher(code).matches()) {
            return null;
        }
        JsonNode indexNode = item.get("index");
        boolean hasIndex = indexNode != null && !indexNode.isNull();
        if (hasIndex && !(indexNode.isIntegralNumber() && indexNode.canConvertToInt())) {
            return null;
        }
        Integer index = hasIndex ? indexNode.asInt() : null;
        JsonNode unitNode = item.get("unit");
        if (unitNode == null || unitNode.isNull()) {
            String pointer = text(item.get("pointer"));
            boolean valid = code.equals(REPLY_CODE) && !hasIndex && pointer != null
                    && POINTER.matcher(pointer).matches();
            return valid ? new RejectDto(null, null, code, pointer) : null;
        }
        String unit = text(unitNode);
        boolean pointerAbsent = item.get("pointer") == null || item.get("pointer").isNull();
        if (unit == null || !UNITS.contains(unit) || !pointerAbsent) {
            return null;
        }
        boolean order = unit.equals("ORDER");
        if (order != hasIndex || (hasIndex && (index < 0 || index >= MAX_ORDERS))) {
            return null;
        }
        return new RejectDto(unit, index, code, null);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
