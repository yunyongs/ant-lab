package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.account.CashLedger;
import antlab.account.Execution;
import antlab.account.LedgerType;
import antlab.account.Order;
import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.account.OrderStatus;
import antlab.account.OrderType;
import antlab.history.MoveDirection;
import antlab.member.MemberRepository;
import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationLog;
import antlab.scoring.ReconciliationStatus;
import antlab.season.FeedKind;
import antlab.season.Formation;
import antlab.season.FormationChange;
import antlab.season.Season;
import antlab.season.SeasonPath;
import antlab.season.SeasonRepository;
import antlab.season.Turn;
import antlab.season.TurnFeed;
import antlab.season.TurnPrice;
import antlab.support.FieldMutator;
import antlab.support.JpaMappingTest;
import antlab.support.MappingModel;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToOne;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.query.sqm.InterpretationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 추가 전용 9개: 넣을 수는 있고, 넣은 뒤에는 필드를 바꿔 flush 해도 UPDATE 가 나가지 않으며 JPQL 일괄 UPDATE 는 거부된다.
 * 예전에는 SQL 로 값을 바꾸는 길만 남는데, 그것은 DB 권한(MVP)이 막는다.
 */
@JpaMappingTest
class AppendOnlyEntityTest {

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

    SeasonAccountFixture fixture;
    Season season;
    CashAccount account;
    long assetId;
    long blockId;

    @BeforeEach
    void 부모_행() {
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        season = fixture.newSeason(fixture.newMember());
        account = fixture.newAccount(season);
        assetId = fixture.newAssetId();
        blockId = fixture.newMonthBlockId();
    }

    /** 9개 엔티티의 표본. 체결은 주문을, 원장은 계좌를 먼저 만든 뒤 쓴다. */
    private Map<String, Object> samples() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season_path", new SeasonPath(season, 1, blockId));
        m.put("turn", new Turn(season, 1, bd("1000"), bd("0"), bd("1000"), bd("1000"), bd("0"), bd("0"), bd("0"),
                bd("0")));
        m.put("turn_price", new TurnPrice(season, 0, assetId, bd("1500")));
        m.put("formation_change", new FormationChange(season, 1, new Formation(20, 30, 30, 20),
                new Formation(10, 20, 30, 40), "사유", Instant.now()));
        m.put("turn_feed", new TurnFeed(season, 1, 1, FeedKind.NUDGE, null, null, null, false, MoveDirection.UP, 2,
                0, "tmpl", "{\"a\": 1}", "본문"));
        m.put("reconciliation_log", new ReconciliationLog(season.getId(), 1, 1, CheckCode.CASH_SUM,
                ReconciliationStatus.MATCH, bd("100"), bd("100"), bd("0"), 0, null, "{\"k\": [1, 2]}"));
        Order order = new Order(season.getId(), assetId, 1, OrderSide.BUY, OrderType.MARKET, 3, null,
                OrderStatus.FILLED, OrderSource.DIRECT, null, null, Instant.now());
        m.put("orders", order);
        m.put("execution", null); // 주문이 저장된 뒤 채운다
        m.put("cash_ledger", new CashLedger(account, LedgerType.DEPOSIT, bd("10000000"), bd("10000000"), null));
        return m;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private String row(String table, Object id) {
        return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM " + table + " t WHERE id = ?", String.class, id);
    }

    private static Object idOf(Object entity) throws Exception {
        Field f = MappingModel.fields(entity.getClass()).stream().filter(MappingModel::isId).findFirst().orElseThrow();
        f.setAccessible(true);
        return f.get(entity);
    }

    @Test
    void 아홉_개_모두_저장되고_시각은_시계에서_온다() throws Exception {
        Instant before = Instant.now();
        Map<String, Object> samples = samples();
        for (Map.Entry<String, Object> e : samples.entrySet()) {
            Object entity = e.getValue();
            if (entity == null) {
                Order order = (Order) samples.get("orders");
                entity = new Execution(order.getId(), bd("1500"), 3, bd("7"), bd("0"), null);
            }
            em.persist(entity);
            assertThat(idOf(entity)).as(e.getKey() + " 키").isNotNull();
            Instant created = ((antlab.common.persistence.CreatedEntity) entity).getCreatedAt();
            assertThat(created).as(e.getKey() + " created_at").isBetween(before, Instant.now().plusSeconds(1));
            assertThat(Duration.between(before, created)).isLessThan(Duration.ofMinutes(1));
        }
    }

    @Test
    void 필드를_바꾸고_flush_해도_UPDATE가_나가지_않고_DB_행은_그대로다() throws Exception {
        Map<String, Object> samples = samples();
        int checked = 0;
        for (Map.Entry<String, Object> e : samples.entrySet()) {
            Object entity = e.getValue();
            if (entity == null) {
                entity = new Execution(((Order) samples.get("orders")).getId(), bd("1500"), 3, bd("7"), bd("0"), null);
            }
            String table = e.getKey();
            em.persist(entity);
            Object id = idOf(entity);
            em.flush();
            em.clear();
            String before = row(table, id);

            SqlRecorder.start();
            Object loaded = em.find(entity.getClass(), id);
            int changed = 0;
            for (Field f : MappingModel.fields(entity.getClass())) {
                if (MappingModel.isId(f) || f.isAnnotationPresent(ManyToOne.class)) {
                    continue;
                }
                f.setAccessible(true);
                Object next = FieldMutator.changeOf(f.get(loaded), f.getType());
                if (next != null) {
                    f.set(loaded, next);
                    changed++;
                }
            }
            em.flush();
            List<String> sqls = SqlRecorder.stop();
            em.clear();

            assertThat(changed).as(table + " 에서 바꾼 필드 수").isPositive();
            assertThat(sqls).as(table + " 의 실행 SQL").noneMatch(s -> s.toLowerCase().startsWith("update"));
            assertThat(row(table, id)).as(table + " 의 DB 행").isEqualTo(before);
            checked++;
        }
        assertThat(checked).isEqualTo(9);
    }

    @Test
    void 아홉_개_모두_Immutable이다() {
        for (Class<?> c : List.of(SeasonPath.class, Turn.class, TurnPrice.class, FormationChange.class,
                TurnFeed.class, ReconciliationLog.class, Order.class, Execution.class, CashLedger.class)) {
            assertThat(c.isAnnotationPresent(Immutable.class)).as(c.getSimpleName()).isTrue();
        }
    }

    @Test
    void JPQL_일괄_UPDATE는_거부된다() {
        // Hibernate 7.4 기본값(immutable_entity_update_query_handling_mode = EXCEPTION)을 실측으로 고정한다
        assertThatThrownBy(() -> em.createQuery("update CashLedger c set c.amount = c.amount").executeUpdate())
                .isInstanceOf(InterpretationException.class)
                .hasMessageContaining("update an immutable entity");
    }

    @Test
    void JSONB_문자열은_이중_인코딩_없이_객체로_왕복한다() {
        ReconciliationLog log = new ReconciliationLog(season.getId(), 1, 1, CheckCode.CASH_SUM,
                ReconciliationStatus.MATCH, bd("100"), bd("100"), bd("0"), 0, null, "{\"b\": 2, \"a\": [1, 2]}");
        TurnFeed feed = new TurnFeed(season, 1, 1, FeedKind.NUDGE, null, null, null, false, null, null, 0,
                "tmpl", "{\"x\": \"y\"}", "본문");
        em.persist(log);
        em.persist(feed);
        em.flush();
        em.clear();

        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(detail) FROM reconciliation_log WHERE id = ?",
                String.class, log.getId())).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(template_vars) FROM turn_feed WHERE id = ?",
                String.class, feed.getId())).isEqualTo("object");

        ReconciliationLog loadedLog = em.find(ReconciliationLog.class, log.getId());
        String dbText = jdbc.queryForObject("SELECT detail::text FROM reconciliation_log WHERE id = ?",
                String.class, log.getId());
        assertThat(loadedLog.getDetail()).isEqualTo(dbText).startsWith("{");
        assertThat(em.find(TurnFeed.class, feed.getId()).getTemplateVars()).startsWith("{").contains("\"x\"");
    }

    @Test
    void detail이_null이면_NULL로_저장된다() {
        ReconciliationLog log = new ReconciliationLog(season.getId(), 1, 1, CheckCode.CASH_SUM,
                ReconciliationStatus.MATCH, null, null, null, 0, null, null);
        em.persist(log);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT detail IS NULL FROM reconciliation_log WHERE id = ?",
                Boolean.class, log.getId())).isTrue();
    }

    @Test
    void type_필드로_JPQL_조건을_쓸_수_있다() {
        em.persist(new Order(season.getId(), assetId, 1, OrderSide.BUY, OrderType.MARKET, 3, null,
                OrderStatus.FILLED, OrderSource.DIRECT, null, null, Instant.now()));
        em.flush();
        List<Order> found = em.createQuery("select o from Orders o where o.type = :t and o.seasonId = :s", Order.class)
                .setParameter("t", OrderType.MARKET).setParameter("s", season.getId()).getResultList();
        assertThat(found).hasSize(1);
        List<CashLedger> none = em.createQuery("select l from CashLedger l where l.type = :t", CashLedger.class)
                .setParameter("t", LedgerType.TAX).getResultList();
        assertThat(none).isEmpty();
    }

    @Test
    void 포메이션_임베더블이_왕복한다() {
        FormationChange fc = new FormationChange(season, 2, new Formation(20, 30, 30, 20),
                new Formation(5, 15, 25, 55), null, Instant.now());
        em.persist(fc);
        em.flush();
        em.clear();
        FormationChange loaded = em.find(FormationChange.class, fc.getId());
        assertThat(loaded.getBefore()).isEqualTo(new Formation(20, 30, 30, 20));
        assertThat(loaded.getAfter()).isEqualTo(new Formation(5, 15, 25, 55));
        assertThat(jdbc.queryForObject(
                "SELECT after_forward FROM formation_change WHERE id = ?", Integer.class, fc.getId())).isEqualTo(55);
    }
}
