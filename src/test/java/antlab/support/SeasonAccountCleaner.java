package antlab.support;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 커밋하는 시험이 자기가 만든 행만 지운다. 외래 키가 {@code RESTRICT} 라 자식 → 부모 순서가 고정이다:
 * cash_ledger·execution → orders → holding·lineup·turn_feed·formation_change·reconciliation_log·turn·turn_price·season_path
 * → cash_account → season → member, 그 뒤 종목·그룹·월 블록.
 */
public final class SeasonAccountCleaner {

    private SeasonAccountCleaner() {
    }

    public static void delete(JdbcTemplate jdbc, Collection<Long> seasonIds, Collection<Long> memberIds,
            Collection<Long> assetIds, Collection<Long> groupIds, Collection<Long> blockIds) {
        for (Long s : seasonIds) {
            jdbc.update("DELETE FROM cash_ledger WHERE season_id = ?", s);
            jdbc.update("DELETE FROM execution WHERE order_id IN (SELECT id FROM orders WHERE season_id = ?)", s);
            jdbc.update("DELETE FROM orders WHERE season_id = ?", s);
            for (String table : new String[] {"holding", "lineup", "turn_feed", "formation_change",
                    "reconciliation_log", "turn", "turn_price", "season_path", "cash_account"}) {
                jdbc.update("DELETE FROM " + table + " WHERE season_id = ?", s);
            }
            jdbc.update("DELETE FROM season WHERE id = ?", s);
        }
        for (Long m : memberIds) {
            jdbc.update("DELETE FROM member WHERE id = ?", m);
        }
        for (Long a : assetIds) {
            jdbc.update("DELETE FROM asset WHERE id = ?", a);
        }
        for (Long g : groupIds) {
            jdbc.update("DELETE FROM asset_group WHERE id = ?", g);
        }
        for (Long b : blockIds) {
            jdbc.update("DELETE FROM month_block WHERE id = ?", b);
        }
    }
}
