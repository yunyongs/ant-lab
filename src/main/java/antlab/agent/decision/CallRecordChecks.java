package antlab.agent.decision;

import antlab.agent.AgentCallRepository;
import antlab.agent.ModelRoute;
import antlab.agent.SeasonAgentConfig;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rules every stored model call must meet, whichever route records it (a turn decision or a memory submit): no NUL, no
 * text over 256 KiB, nothing that looks like a secret, the model of the season config, and a ledger call id on an
 * OPENROUTER season. Each failure is an INVALID_ARGUMENT that never carries the offending text.
 */
public final class CallRecordChecks {

    public static final int MAX_TEXT_BYTES = 262_144;
    /** Key shapes only: a key starts a token (no letter, digit, '_' or '-' before "sk-"); a bearer value has a digit. */
    private static final Pattern NO_KEYS = Pattern.compile(
            "(?<![A-Za-z0-9_-])sk-(?:or-v1-[A-Za-z0-9]{16,}|ant-[A-Za-z0-9_-]{20,}|proj-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{20,})"
            + "|(?i:\\bbearer\\s+(?=[A-Za-z0-9._~+/=-]*\\d)[A-Za-z0-9._~+/=-]{16,})");

    private CallRecordChecks() {
    }

    public static boolean looksLikeSecret(String text) {
        return NO_KEYS.matcher(text).find();
    }

    /** One ledger call id belongs to one call of the request. */
    public static void requireDistinctLedgerIds(List<CallRecordRequest> records) {
        List<UUID> ledgerIds = records.stream().map(CallRecordRequest::ledgerCallId).filter(Objects::nonNull).toList();
        if (ledgerIds.stream().distinct().count() != ledgerIds.size()) {
            throw invalid("장부 호출 번호가 겹친다");
        }
    }

    /** A ledger call id belongs to one stored call (uq_agent_call_ledger); a repeat is an input error, not a 500. */
    public static void requireUnusedLedgerIds(List<CallRecordRequest> fresh, AgentCallRepository calls) {
        for (CallRecordRequest c : fresh) {
            if (c.ledgerCallId() != null && calls.existsByLedgerCallId(c.ledgerCallId())) {
                throw invalid("장부 호출 번호가 이미 다른 호출에 쓰였다");
            }
        }
    }

    /** The texts, the small strings, the model and the ledger id of one call. */
    public static void checkRecord(CallRecordRequest c, SeasonAgentConfig config) {
        textOk(c.requestText());
        textOk(c.responseText());
        for (String small : new String[] {c.modelRequested(), c.modelActual(), c.provider()}) {
            if (small != null && (small.indexOf('\u0000') >= 0 || looksLikeSecret(small))) {
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
        if (looksLikeSecret(text)) {
            throw invalid("호출 기록에 비밀값처럼 보이는 문자열이 있다");
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
