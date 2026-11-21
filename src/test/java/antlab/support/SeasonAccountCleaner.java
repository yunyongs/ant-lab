package antlab.support;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 커밋하는 시험이 자기가 만든 행만 지운다. 외래 키가 {@code RESTRICT} 라 자식 → 부모 순서가 고정이다:
 * agent_call → season_agent_config → agent_memory → agent_reflection → cash_ledger·execution → orders → holding·lineup·turn_feed·formation_change·reconciliation_log·turn·turn_price·season_path
 * → cash_account → season → agent_profile → refresh_token → member, 그 뒤 종목·그룹·월 블록.
 */
public final class SeasonAccountCleaner {

    private SeasonAccountCleaner() {
    }

    public static void delete(JdbcTemplate jdbc, Collection<Long> seasonIds, Collection<Long> memberIds,
            Collection<Long> assetIds, Collection<Long> groupIds, Collection<Long> blockIds) {
        // a season may pin a memory version written from another season: drop every config before any memory row
        for (Long s : seasonIds) {
            jdbc.update("DELETE FROM league_entry WHERE season_id = ?", s);
            jdbc.update("DELETE FROM agent_call WHERE season_id = ?", s);
            jdbc.update("DELETE FROM season_agent_config WHERE season_id = ?", s);
        }
        for (Long s : seasonIds) {
            jdbc.update("DELETE FROM agent_memory WHERE source_season_id = ?", s);
            jdbc.update("DELETE FROM agent_reflection WHERE season_id = ?", s);
        }
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
            // a sibling points at its group root with a RESTRICT key, so the pointers go first
            jdbc.update("UPDATE league SET seed_league_id = NULL WHERE owner_member_id = ?", m);
            jdbc.update("DELETE FROM league WHERE owner_member_id = ?", m);
            jdbc.update("DELETE FROM agent_profile WHERE member_id = ?", m);
            jdbc.update("DELETE FROM refresh_token WHERE member_id = ?", m);
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
