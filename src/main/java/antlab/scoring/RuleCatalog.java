package antlab.scoring;

import java.util.List;
import java.util.Optional;

/**
 * 대사 규칙 17행 — {@code db/22_reconcile_season_def.sql} 의 {@code recon_code} 뷰와 erd 7.5 의 사본.
 * 규칙 5(DDL 로 이동)와 10(권한)은 쿼리 대상이 아니라 없다. 11·19 는 턴 전환 전용(manual = false)이라 이 기능은 행만 둔다.
 */
public final class RuleCatalog {

    /** 규칙 하나: 규칙 번호, 검사 코드, 수동 검사 대상 여부, 스칼라(세 값을 채우는) 규칙 여부. */
    public record Rule(int ruleNo, CheckCode checkCode, boolean manual, boolean scalar) {
    }

    private static final List<Rule> ALL = List.of(
            new Rule(1, CheckCode.ACCOUNT_OPENING, true, false),
            new Rule(2, CheckCode.CASH_SUM, true, true),
            new Rule(3, CheckCode.LEDGER_CHAIN, true, false),
            new Rule(4, CheckCode.ORDER_LEDGER, true, true),
            new Rule(6, CheckCode.HOLDING_QTY, true, true),
            new Rule(7, CheckCode.ORDER_FILL, true, false),
            new Rule(8, CheckCode.EXEC_VALUES, true, false),
            new Rule(9, CheckCode.RESERVED_ZERO, true, true),
            new Rule(11, CheckCode.VALUATION, false, false),
            new Rule(12, CheckCode.TURN_PRICE, true, false),
            new Rule(13, CheckCode.FILL_PRICE, true, false),
            new Rule(14, CheckCode.FEE_RULE, true, true),
            new Rule(15, CheckCode.FORMATION, true, false),
            new Rule(16, CheckCode.FORMATION_BIND, true, false),
            new Rule(17, CheckCode.LINEUP_HOLDING, true, false),
            new Rule(18, CheckCode.LINEUP_OVERLAP, true, false),
            new Rule(19, CheckCode.POSITION_VALUE, false, false));

    private RuleCatalog() {
    }

    /** 전체 17행, 규칙 번호 순. */
    public static List<Rule> allRules() {
        return ALL;
    }

    /** 수동 검사 15행, 규칙 번호 순. */
    public static List<Rule> manualRules() {
        return ALL.stream().filter(Rule::manual).toList();
    }

    public static List<CheckCode> allCodes() {
        return ALL.stream().map(Rule::checkCode).toList();
    }

    public static List<CheckCode> manualCodes() {
        return manualRules().stream().map(Rule::checkCode).toList();
    }

    public static Optional<Rule> byRuleNo(int ruleNo) {
        return ALL.stream().filter(r -> r.ruleNo() == ruleNo).findFirst();
    }
}
