package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** SeasonPath 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface SeasonPathRepository extends AppendOnlyRepository<SeasonPath, Long> {

    /** 그 턴의 경로 행이 있는지. 블록 번호는 읽지 않는다. */
    boolean existsBySeasonIdAndTurnNo(Long seasonId, int turnNo);

    /**
     * 턴 가격 계산 입력 한 문장: 시즌의 0턴 가격(시작값) × 경로 1..upto 턴 × 그 달의 종목 수익률·환율 변화.
     * 수익률·환율이 없는 칸은 null 로 남겨 호출자가 완전성을 판단한다. 월 블록 번호는 투영에 넣지 않고(SPRING_JPA §4.6),
     * {@code turn_no <= upto} 조건이 다음 턴 뒤의 달을 읽지 않게 한다(사후 정보 배제). 종목 번호, 턴 번호 순.
     */
    @Query(nativeQuery = true, value = """
            SELECT tp.asset_id AS "assetId", tp.price AS "initialPrice", a.currency AS "currency",
                   CAST(sp.turn_no AS integer) AS "turnNo", amr.local_return AS "localReturn", fx.fx_change AS "fxChange"
            FROM turn_price tp
            JOIN asset a ON a.id = tp.asset_id
            JOIN season_path sp ON sp.season_id = tp.season_id AND sp.turn_no BETWEEN 1 AND CAST(:uptoTurn AS smallint)
            LEFT JOIN asset_month_return amr ON amr.asset_id = tp.asset_id AND amr.month_block_id = sp.month_block_id
            LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency = a.currency
            WHERE tp.season_id = :seasonId AND tp.turn_no = 0
            ORDER BY tp.asset_id, sp.turn_no
            """)
    List<TurnFactorRow> findTurnFactors(@Param("seasonId") long seasonId, @Param("uptoTurn") int uptoTurn);

    /**
     * Review only: each turn's real month and its one-line summary, turn order. Reads {@code [복기 전용]} values, hence the
     * name; {@code SeasonReviewService} is the only caller and calls it after the FINISHED check. No block id is projected.
     */
    @Query("select new antlab.season.RevealedMonthRow(sp.turnNo, mb.monthStart, mb.eventSummary) "
            + "from SeasonPath sp join MonthBlock mb on mb.id = sp.monthBlockId "
            + "where sp.season.id = :seasonId order by sp.turnNo")
    List<RevealedMonthRow> findRevealedMonths(@Param("seasonId") long seasonId);

    /** Review only: event tags of each turn's real month, turn order then event key. See {@link #findRevealedMonths}. */
    @Query("select new antlab.season.RevealedEventRow(sp.turnNo, me.category, me.eventType, me.phase, me.title, "
            + "me.hindsight, me.source) "
            + "from SeasonPath sp join MonthEvent me on me.monthBlockId = sp.monthBlockId "
            + "where sp.season.id = :seasonId order by sp.turnNo, me.eventKey")
    List<RevealedEventRow> findRevealedEvents(@Param("seasonId") long seasonId);
}
