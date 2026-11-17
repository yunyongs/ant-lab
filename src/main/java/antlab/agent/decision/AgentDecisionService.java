package antlab.agent.decision;

import antlab.account.OrderCheck;
import antlab.account.OrderRepository;
import antlab.account.OrderResultDto;
import antlab.account.OrderScope;
import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.account.PlaceOrderRequest;
import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentCallStatus;
import antlab.agent.DecisionOutcome;
import antlab.agent.ModelRoute;
import antlab.agent.RejectDto;
import antlab.agent.SeasonAgentConfig;
import antlab.agent.SeasonAgentConfigRepository;
import antlab.agent.decision.DecisionResultDto.FormationResultDto;
import antlab.agent.decision.DecisionResultDto.OrderUnitResultDto;
import antlab.agent.decision.DecisionResultDto.SchemaViolationDto;
import antlab.agent.decision.DecisionResultDto.ThesesResultDto;
import antlab.asset.Asset;
import antlab.asset.AssetRepository;
import antlab.asset.Market;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.FormationChange;
import antlab.season.FormationChangeRepository;
import antlab.season.FormationCheck;
import antlab.season.FormationChangeService;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One decision of an agent for one turn: one request = one transaction. The season is locked once, each unit of the reply
 * is checked and applied in a fixed order on the state the previous unit left (formation, then sells in the reply's order,
 * then buys in the reply's order), and the attempts plus the final call row are written in the same transaction. A unit
 * that fails its check is recorded as a reject and the other units still run; a broken state (ledger, lineup, formation
 * chain, event time) throws and rolls everything back. The units run through the same check and apply code as the human
 * order and formation endpoints, so the same rules, prices and fees hold.
 *
 * <p>Idempotency: the final call row carries the SHA-256 of the canonical outcome and decision. The same decision sent
 * again answers the stored result; a different one for a decided turn is DECISION_CONFLICT.
 */
@Service
public class AgentDecisionService {

    static final int MAX_TEXT_BYTES = 262_144;
    /** Key shapes only: a key starts a token (no letter, digit, '_' or '-' before "sk-"); a bearer value has a digit. */
    private static final Pattern NO_KEYS = Pattern.compile(
            "(?<![A-Za-z0-9_-])sk-(?:or-v1-[A-Za-z0-9]{16,}|ant-[A-Za-z0-9_-]{20,}|proj-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{20,})"
            + "|(?i:\\bbearer\\s+(?=[A-Za-z0-9._~+/=-]*\\d)[A-Za-z0-9._~+/=-]{16,})");
    private static final String UNIT_FORMATION = "FORMATION";
    private static final String UNIT_ORDER = "ORDER";
    private static final String UNIT_THESES = "THESES";
    private static final String REPLY_POINTER = "/reply";

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final SeasonAgentConfigRepository configs;
    private final AgentCallRepository calls;
    private final OrderService orders;
    private final FormationChangeService formations;
    private final AssetRepository assets;
    private final OrderRepository orderRows;
    private final FormationChangeRepository formationRows;
    private final JsonMapper mapper;

    public AgentDecisionService(CurrentMember currentMember, SeasonRepository seasons,
            SeasonAgentConfigRepository configs, AgentCallRepository calls, OrderService orders,
            FormationChangeService formations, AssetRepository assets, OrderRepository orderRows,
            FormationChangeRepository formationRows, JsonMapper mapper) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.configs = configs;
        this.calls = calls;
        this.orders = orders;
        this.formations = formations;
        this.assets = assets;
        this.orderRows = orderRows;
        this.formationRows = formationRows;
        this.mapper = mapper;
    }

    @Transactional
    public DecisionResultDto submit(long agentId, long seasonId, int turnNo, DecisionRequest request) {
        requireActing(agentId);
        checkEnvelope(request);
        OrderScope scope = orders.lock(seasonId, turnNo);
        Season season = scope.season();
        SeasonAgentConfig config = configs.findById(seasonId).orElseThrow(AgentDecisionService::notFound);

        String hash = hash(request);
        Optional<AgentCall> decided = calls.findFinalBySeasonIdAndTurnNo(seasonId, turnNo, AgentCallKind.TURN_DECISION);
        if (decided.isPresent()) {
            if (!hash.equals(decided.get().getRequestSha256())) {
                throw new DomainException(ErrorCode.DECISION_CONFLICT, "이 턴에는 다른 결정이 이미 기록되었다");
            }
            return rebuild(season, turnNo, decided.get(), true);
        }

        List<AgentCall> stored = calls.findAllBySeasonIdAndTurnNo(seasonId, turnNo);
        checkCalls(request.calls(), config, true, request.outcome());
        List<CallRecordRequest> fresh = reconcile(stored, request.calls(), true);
        requireUnusedLedgerIds(fresh);

        Applied applied = decide(scope, turnNo, request);
        for (int i = 0; i < fresh.size(); i++) {
            CallRecordRequest call = fresh.get(i);
            boolean last = i == fresh.size() - 1;
            calls.save(row(seasonId, turnNo, call, last ? applied : null, last ? hash : null));
        }
        return applied.result(seasonId, turnNo, false);
    }

    /** Stores attempts made before the decision (a pause, a wait); already stored identical attempts are skipped. */
    @Transactional
    public void flush(long agentId, long seasonId, int turnNo, List<CallRecordRequest> records) {
        requireActing(agentId);
        if (records == null || records.isEmpty() || records.size() > 20) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "호출 기록은 1개 이상 20개 이하여야 한다");
        }
        long memberId = currentMember.existingId().orElseThrow(AgentDecisionService::notFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(AgentDecisionService::notFound);
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "진행 중인 시즌이 아니다");
        }
        if (season.getCurrentTurnNo() != turnNo) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 기록이 본 턴이 다르다");
        }
        SeasonAgentConfig config = configs.findById(seasonId).orElseThrow(AgentDecisionService::notFound);
        checkCalls(records, config, false, null);
        List<AgentCall> stored = calls.findAllBySeasonIdAndTurnNo(seasonId, turnNo);
        List<CallRecordRequest> fresh = reconcile(stored, records, false);
        requireUnusedLedgerIds(fresh);
        boolean decided = stored.stream().anyMatch(AgentCall::isFinalCall);
        if (decided && !fresh.isEmpty()) {
            throw new DomainException(ErrorCode.DECISION_CONFLICT, "이 턴은 이미 결정되어 호출을 더할 수 없다");
        }
        fresh.forEach(call -> calls.save(row(seasonId, turnNo, call, null, null)));
    }

    /** The stored result of a decided turn. */
    @Transactional(readOnly = true)
    public DecisionResultDto read(long agentId, long seasonId, int turnNo) {
        requireActing(agentId);
        long memberId = currentMember.existingId().orElseThrow(AgentDecisionService::notFound);
        Season season = seasons.findByIdAndMemberId(seasonId, memberId).orElseThrow(AgentDecisionService::notFound);
        if (turnNo > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
        }
        AgentCall decided = calls.findFinalBySeasonIdAndTurnNo(seasonId, turnNo, AgentCallKind.TURN_DECISION)
                .orElseThrow(AgentDecisionService::notFound);
        return rebuild(season, turnNo, decided, true);
    }

    // ---- units -------------------------------------------------------------------------------------------------

    /** What the engine did with the reply: the verdict, the unit results and the reject list to record. */
    private record Applied(DecisionOutcome outcome, String parsed, List<SchemaViolation> violations,
            FormationResultDto formation, List<OrderUnitResultDto> orders, ThesesResultDto theses,
            List<RejectDto> rejects) {

        DecisionResultDto result(long seasonId, int turnNo, boolean replayed) {
            return new DecisionResultDto(seasonId, turnNo, outcome, replayed, formation, orders, theses,
                    violations.stream().map(v -> new SchemaViolationDto(v.pointer(), v.keyword())).toList());
        }
    }

    private Applied decide(OrderScope scope, int turnNo, DecisionRequest request) {
        FormationResultDto none = new FormationResultDto(UnitStatus.NONE, null, null);
        ThesesResultDto noTheses = new ThesesResultDto(UnitStatus.NONE, null);
        if (request.outcome() == DecisionOutcome.NO_RESPONSE) {
            return new Applied(DecisionOutcome.NO_RESPONSE, null, List.of(), none, List.of(), noTheses, null);
        }
        if (request.outcome() == DecisionOutcome.INVALID) {
            return new Applied(DecisionOutcome.INVALID, null, List.of(), none, List.of(), noTheses,
                    List.of(replyReject(REPLY_POINTER)));
        }
        String parsed = canonical(request.decision()).toString();
        TurnDecisionParser.Result shape = TurnDecisionParser.parse(request.decision());
        if (!shape.valid()) {
            return new Applied(DecisionOutcome.INVALID, parsed, shape.violations(), none, List.of(), noTheses,
                    shape.violations().stream().map(v -> replyReject(v.pointer().isEmpty() ? REPLY_POINTER : v.pointer()))
                            .toList());
        }
        TurnDecision decision = shape.decision();
        List<RejectDto> rejects = new ArrayList<>();
        FormationResultDto formation = formationUnit(scope, decision, rejects);
        List<OrderUnitResultDto> orderResults = orderUnits(scope, turnNo, decision, rejects);
        ThesesResultDto theses = new ThesesResultDto(UnitStatus.NONE, null);
        if (decision.thesesCount() > 0) {
            theses = new ThesesResultDto(UnitStatus.REJECTED, DecisionRejectCode.THESES_NOT_SUPPORTED.name());
            rejects.add(new RejectDto(UNIT_THESES, null, DecisionRejectCode.THESES_NOT_SUPPORTED.name(), null));
        }
        rejects.sort(Comparator.comparingInt((RejectDto r) -> unitRank(r.unit()))
                .thenComparing(r -> r.index() == null ? -1 : r.index()));
        return new Applied(DecisionOutcome.DECISION, parsed, List.of(), formation, orderResults, theses, rejects);
    }

    private FormationResultDto formationUnit(OrderScope scope, TurnDecision decision, List<RejectDto> rejects) {
        TurnDecision.FormationProposal f = decision.formationChange();
        if (f == null) {
            return new FormationResultDto(UnitStatus.NONE, null, null);
        }
        FormationCheck checked = formations.check(scope.season(), f.goalkeeper(), f.defender(), f.midfielder(),
                f.forward(), f.reason());
        if (checked.rejection() != null) {
            String code = checked.rejection().name();
            rejects.add(new RejectDto(UNIT_FORMATION, null, code, null));
            return new FormationResultDto(UnitStatus.REJECTED, code, null);
        }
        formations.apply(checked.plan());
        Long id = formationRows.findLastBySeasonIdAndTurnNo(scope.season().getId(), scope.turnNo())
                .map(FormationChange::getId).orElseThrow(() -> new IllegalStateException("formation change not saved"));
        return new FormationResultDto(UnitStatus.APPLIED, null, id);
    }

    /** Sells before buys, each group in the reply's order; the results come back in the reply's order. */
    private List<OrderUnitResultDto> orderUnits(OrderScope scope, int turnNo, TurnDecision decision,
            List<RejectDto> rejects) {
        List<TurnDecision.OrderProposal> proposals = decision.orders();
        OrderUnitResultDto[] results = new OrderUnitResultDto[proposals.size()];
        for (int index : applicationOrder(proposals)) {
            TurnDecision.OrderProposal order = proposals.get(index);
            Optional<DecisionRejectCode> reject = Optional.empty();
            OrderCheck checked = null;
            if (order.source() == OrderSource.BENCH) {
                reject = Optional.of(DecisionRejectCode.SOURCE_NOT_ALLOWED);
            } else if (order.source() != OrderSource.DIRECT) {
                reject = Optional.of(DecisionRejectCode.SOURCE_NOT_VISIBLE);
            } else {
                Optional<Asset> asset = asset(order.asset());
                if (asset.isEmpty()) {
                    reject = Optional.of(DecisionRejectCode.ASSET_NOT_IN_POOL);
                } else {
                    checked = orders.check(scope, new PlaceOrderRequest(turnNo, order.side(), asset.get().getId(),
                            order.quantity(), order.position(), null));
                    if (checked.rejection() != null) {
                        reject = Optional.of(DecisionRejectCode.valueOf(checked.rejection().name()));
                    }
                }
            }
            if (reject.isPresent()) {
                String code = reject.get().name();
                rejects.add(new RejectDto(UNIT_ORDER, index, code, null));
                results[index] = new OrderUnitResultDto(index, UnitStatus.REJECTED, code, null);
            } else {
                OrderResultDto filled = orders.apply(checked.plan());
                results[index] = new OrderUnitResultDto(index, UnitStatus.APPLIED, null, filled.orderId());
            }
        }
        return List.of(results);
    }

    /** Indexes of the orders in the order they are applied: sells in the reply's order, then buys in the reply's order. */
    static List<Integer> applicationOrder(List<TurnDecision.OrderProposal> proposals) {
        List<Integer> sequence = new ArrayList<>();
        for (OrderSide side : List.of(OrderSide.SELL, OrderSide.BUY)) {
            for (int i = 0; i < proposals.size(); i++) {
                if (proposals.get(i).side() == side) {
                    sequence.add(i);
                }
            }
        }
        return sequence;
    }

    private Optional<Asset> asset(String text) {
        int colon = text.indexOf(':');
        return assets.findByMarketAndTicker(Market.valueOf(text.substring(0, colon)), text.substring(colon + 1));
    }

    private static RejectDto replyReject(String pointer) {
        return new RejectDto(null, null, DecisionRejectCode.SCHEMA_INVALID.name(), pointer);
    }

    private static int unitRank(String unit) {
        if (unit == null) {
            return 0;
        }
        return switch (unit) {
            case UNIT_FORMATION -> 1;
            case UNIT_ORDER -> 2;
            default -> 3;
        };
    }

    // ---- stored result -----------------------------------------------------------------------------------------

    /** Rebuilds the result of a decided turn from the final row, the stored reply and the rows the units wrote. */
    private DecisionResultDto rebuild(Season season, int turnNo, AgentCall fin, boolean replayed) {
        long seasonId = season.getId();
        List<RejectDto> rejects = fin.getRejects() == null ? List.of() : readRejects(fin.getRejects());
        FormationResultDto none = new FormationResultDto(UnitStatus.NONE, null, null);
        ThesesResultDto noTheses = new ThesesResultDto(UnitStatus.NONE, null);
        if (fin.getOutcome() != DecisionOutcome.DECISION) {
            List<SchemaViolation> violations = fin.getParsed() == null ? List.of()
                    : TurnDecisionParser.parse(mapper.readTree(fin.getParsed())).violations();
            return new Applied(fin.getOutcome(), fin.getParsed(), violations, none, List.of(), noTheses, rejects)
                    .result(seasonId, turnNo, replayed);
        }
        TurnDecision decision = TurnDecisionParser.parse(mapper.readTree(fin.getParsed())).decision();
        if (decision == null) {
            throw new IllegalStateException("agent_call.parsed of a decided turn does not match the schema");
        }
        FormationResultDto formation = none;
        if (decision.formationChange() != null) {
            Optional<RejectDto> rejected = rejects.stream().filter(r -> UNIT_FORMATION.equals(r.unit())).findFirst();
            formation = rejected.map(r -> new FormationResultDto(UnitStatus.REJECTED, r.code(), null))
                    .orElseGet(() -> new FormationResultDto(UnitStatus.APPLIED, null,
                            formationRows.findLastBySeasonIdAndTurnNo(seasonId, turnNo).map(FormationChange::getId)
                                    .orElseThrow(() -> new IllegalStateException("applied formation change not found"))));
        }
        List<Long> written = orderRows.findIdsBySeasonIdAndTurnNo(seasonId, turnNo);
        OrderUnitResultDto[] results = new OrderUnitResultDto[decision.orders().size()];
        int next = 0;
        for (int index : applicationOrder(decision.orders())) {
            final int at = index;
            Optional<RejectDto> rejected = rejects.stream()
                    .filter(r -> UNIT_ORDER.equals(r.unit()) && r.index() != null && r.index() == at).findFirst();
            if (rejected.isPresent()) {
                results[index] = new OrderUnitResultDto(index, UnitStatus.REJECTED, rejected.get().code(), null);
            } else {
                if (next >= written.size()) {
                    throw new IllegalStateException("applied order not found");
                }
                results[index] = new OrderUnitResultDto(index, UnitStatus.APPLIED, null, written.get(next++));
            }
        }
        if (next != written.size()) {
            throw new IllegalStateException("orders of the turn do not match the stored decision");
        }
        ThesesResultDto theses = rejects.stream().filter(r -> UNIT_THESES.equals(r.unit())).findFirst()
                .map(r -> new ThesesResultDto(UnitStatus.REJECTED, r.code())).orElse(noTheses);
        return new Applied(DecisionOutcome.DECISION, fin.getParsed(), List.of(), formation, List.of(results), theses,
                rejects).result(seasonId, turnNo, replayed);
    }

    // ---- envelope and attempts ---------------------------------------------------------------------------------

    /** Request-level rules that need no database: nothing is recorded when one fails. */
    private void checkEnvelope(DecisionRequest request) {
        if (request.outcome() == DecisionOutcome.DECISION) {
            if (request.decision() == null || !request.decision().isObject()) {
                throw invalid("DECISION 에는 결정 객체가 필요하다");
            }
            if (request.decision().toString().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                throw invalid("결정이 너무 길다");
            }
            if (hasNul(request.decision())) {
                throw invalid("결정에 저장할 수 없는 문자가 있다");
            }
            if (NO_KEYS.matcher(request.decision().toString()).find()) {
                throw invalid("결정에 비밀값처럼 보이는 문자열이 있다");
            }
        } else if (request.decision() != null && !request.decision().isNull()) {
            throw invalid("DECISION 이 아니면 결정 객체를 보내지 않는다");
        }
    }

    /** True when any string value or key of the reply holds a NUL character, which the database cannot store. */
    private static boolean hasNul(JsonNode node) {
        if (node.isString()) {
            return node.asString().indexOf('\u0000') >= 0;
        }
        if (node.isObject()) {
            return node.propertyNames().stream().anyMatch(name -> name.indexOf('\u0000') >= 0 || hasNul(node.get(name)));
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasNul(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Per-call rules; {@code decision} is true for the submit, where the last call is the final one. */
    private void checkCalls(List<CallRecordRequest> records, SeasonAgentConfig config, boolean decision,
            DecisionOutcome outcome) {
        List<UUID> ledgerIds = records.stream().map(CallRecordRequest::ledgerCallId).filter(Objects::nonNull).toList();
        if (ledgerIds.stream().distinct().count() != ledgerIds.size()) {
            throw invalid("장부 호출 번호가 겹친다");
        }
        for (int i = 0; i < records.size(); i++) {
            CallRecordRequest c = records.get(i);
            if (c.attemptNo() != records.get(0).attemptNo() + i) {
                throw invalid("시도 번호가 이어지지 않는다");
            }
            textOk(c.requestText());
            textOk(c.responseText());
            for (String small : new String[] {c.modelRequested(), c.modelActual(), c.provider()}) {
                if (small != null && (small.indexOf('\u0000') >= 0 || NO_KEYS.matcher(small).find())) {
                    throw invalid("호출 기록에 쓸 수 없는 문자열이 있다");
                }
            }
            if (!c.modelRequested().equals(config.getModelRequested())) {
                throw invalid("호출이 요청한 모델이 시즌 설정의 모델과 다르다");
            }
            if (config.getModelRoute() == ModelRoute.OPENROUTER && c.ledgerCallId() == null) {
                throw invalid("OPENROUTER 시즌의 호출에는 장부 호출 번호가 필요하다");
            }
        }
        if (decision) {
            AgentCallStatus last = records.get(records.size() - 1).status();
            boolean ok = switch (outcome) {
                case DECISION -> last == AgentCallStatus.OK;
                case INVALID -> last == AgentCallStatus.OK || last == AgentCallStatus.INVALID;
                case NO_RESPONSE -> last != AgentCallStatus.OK && last != AgentCallStatus.INVALID;
            };
            if (!ok) {
                throw invalid("마지막 호출의 상태가 결과와 맞지 않는다");
            }
        }
    }

    private static void textOk(String text) {
        if (text == null) {
            return;
        }
        if (text.indexOf('\u0000') >= 0) {
            throw invalid("호출 기록에 쓸 수 없는 문자가 있다");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw invalid("호출 기록의 원문이 너무 길다");
        }
        if (NO_KEYS.matcher(text).find()) {
            throw invalid("호출 기록에 비밀값처럼 보이는 문자열이 있다");
        }
    }

    /**
     * Matches the sent attempts against the stored ones and returns the new ones. Attempts must be numbered one after
     * another and start at or before the first unstored number; a stored attempt sent again must be identical.
     */
    private List<CallRecordRequest> reconcile(List<AgentCall> stored, List<CallRecordRequest> sent, boolean needsNewLast) {
        int storedCount = stored.size();
        int first = sent.get(0).attemptNo();
        if (first > storedCount + 1) {
            throw invalid("시도 번호가 저장된 다음 번호보다 앞서 있다");
        }
        List<CallRecordRequest> fresh = new ArrayList<>();
        for (CallRecordRequest c : sent) {
            if (c.attemptNo() <= storedCount) {
                if (!same(stored.get(c.attemptNo() - 1), c)) {
                    throw new DomainException(ErrorCode.DECISION_CONFLICT, "저장된 시도와 다른 내용이다");
                }
            } else {
                fresh.add(c);
            }
        }
        if (needsNewLast && (fresh.isEmpty() || fresh.get(fresh.size() - 1) != sent.get(sent.size() - 1))) {
            throw invalid("마지막 호출이 이미 저장되어 있다");
        }
        return fresh;
    }

    /** A ledger call id belongs to one stored call (uq_agent_call_ledger); a repeat is an input error, not a 500. */
    private void requireUnusedLedgerIds(List<CallRecordRequest> fresh) {
        for (CallRecordRequest c : fresh) {
            if (c.ledgerCallId() != null && calls.existsByLedgerCallId(c.ledgerCallId())) {
                throw invalid("장부 호출 번호가 이미 다른 호출에 쓰였다");
            }
        }
    }

    private static boolean same(AgentCall s, CallRecordRequest c) {
        return s.getCallKind() == c.kind() && java.util.Objects.equals(s.getLedgerCallId(), c.ledgerCallId())
                && s.getStatus() == c.status() && java.util.Objects.equals(s.getHttpStatus(), c.httpStatus())
                && s.getModelRequested().equals(c.modelRequested())
                && java.util.Objects.equals(s.getModelActual(), c.modelActual())
                && java.util.Objects.equals(s.getProvider(), c.provider()) && s.getRequestText().equals(c.requestText())
                && java.util.Objects.equals(s.getResponseText(), c.responseText())
                && java.util.Objects.equals(s.getTokensIn(), c.tokensIn())
                && java.util.Objects.equals(s.getTokensOut(), c.tokensOut()) && sameMoney(s.getCostUsd(), c.costUsd())
                && s.getLatencyMs() == c.latencyMs();
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private AgentCall row(long seasonId, int turnNo, CallRecordRequest c, Applied fin, String hash) {
        return new AgentCall(seasonId, turnNo, c.attemptNo(), AgentCallKind.TURN_DECISION, c.ledgerCallId(), c.status(),
                c.httpStatus(), c.modelRequested(), c.modelActual(), c.provider(), c.requestText(), c.responseText(),
                fin == null ? null : fin.parsed(), c.tokensIn(), c.tokensOut(), c.costUsd(), c.latencyMs(),
                fin == null ? null : fin.outcome(), fin == null || fin.rejects() == null ? null : rejectsJson(fin.rejects()),
                fin == null ? null : hash);
    }

    /** Reads back what {@link #rejectsJson} wrote; this service is the only writer, so a bad row is damaged data. */
    private List<RejectDto> readRejects(String json) {
        List<RejectDto> out = new ArrayList<>();
        for (JsonNode item : mapper.readTree(json)) {
            JsonNode unit = item.get("unit");
            JsonNode index = item.get("index");
            JsonNode pointer = item.get("pointer");
            out.add(new RejectDto(unit == null || unit.isNull() ? null : unit.asString(),
                    index == null || index.isNull() ? null : index.asInt(), item.get("code").asString(),
                    pointer == null || pointer.isNull() ? null : pointer.asString()));
        }
        return out;
    }

    private String rejectsJson(List<RejectDto> rejects) {
        ArrayNode array = mapper.createArrayNode();
        for (RejectDto r : rejects) {
            ObjectNode item = array.addObject();
            if (r.unit() == null) {
                item.putNull("unit");
            } else {
                item.put("unit", r.unit());
            }
            if (r.index() == null) {
                item.putNull("index");
            } else {
                item.put("index", r.index());
            }
            item.put("code", r.code());
            if (r.pointer() == null) {
                item.putNull("pointer");
            } else {
                item.put("pointer", r.pointer());
            }
        }
        return array.toString();
    }

    // ---- hashing -----------------------------------------------------------------------------------------------

    private String hash(DecisionRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("outcome", request.outcome().name());
        root.set("decision", request.decision() == null ? mapper.nullNode() : canonical(request.decision()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same JSON with object keys sorted, so key order in the request does not change the hash. */
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            node.propertyNames().stream().sorted().forEach(name -> out.set(name, canonical(node.get(name))));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return node;
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
