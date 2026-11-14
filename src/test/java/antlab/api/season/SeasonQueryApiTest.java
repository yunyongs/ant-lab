package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.PROBLEM;
import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.get;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.MemberRepository;
import antlab.account.CashAccountRepository;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET 시즌 목록·상세·턴 가격. 합성 고정본, 시험 트랜잭션이 롤백한다. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class SeasonQueryApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    MemberRepository members;

    @Autowired
    SeasonRepository seasons;

    @Autowired
    CashAccountRepository accounts;

    private static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    private long start() throws Exception {
        return json(post(mvc, VALID_BODY)).get("id").asLong();
    }

    @Test
    void 시즌이_없으면_목록은_빈_배열이다() throws Exception {
        MockHttpServletResponse r = get(mvc, "/api/v1/seasons");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("items").isArray()).isTrue();
        assertThat(json(r).get("items")).isEmpty();
    }

    @Test
    void 목록은_시즌_번호_내림차순이고_잔액을_함께_준다() throws Exception {
        long first = start();
        long second = start();
        JsonNode items = json(get(mvc, "/api/v1/seasons")).get("items");
        List<Long> order = new ArrayList<>();
        items.forEach(i -> order.add(i.get("id").asLong()));
        assertThat(order).containsExactly(second, first);
        assertThat(items.get(0).get("cashBalance").asString()).isEqualTo(items.get(0).get("initialCash").asString());
    }

    @Test
    void 목록_쿼리_수는_시즌_수와_무관하다() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        start();
        start();
        stats.clear();
        get(mvc, "/api/v1/seasons");
        long withTwo = stats.getPrepareStatementCount();
        start();
        stats.clear();
        get(mvc, "/api/v1/seasons");
        long withThree = stats.getPrepareStatementCount();
        assertThat(withThree).isEqualTo(withTwo);
    }

    @Test
    void 상세는_없거나_다른_회원의_시즌이면_404다() throws Exception {
        start();
        assertThat(get(mvc, "/api/v1/seasons/999999999").getStatus()).isEqualTo(404);
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        MockHttpServletResponse r = get(mvc, "/api/v1/seasons/" + others);
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(r.getContentType()).startsWith(PROBLEM.toString());
        assertThat(json(r).get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(get(mvc, "/api/v1/seasons/" + others + "/prices").getStatus()).isEqualTo(404);
        assertThat(json(get(mvc, "/api/v1/seasons")).get("items")).as("목록에도 없다").hasSize(1);
    }

    @Test
    void 시즌_번호가_숫자가_아니면_400이다() {
        assertThat(get(mvc, "/api/v1/seasons/abc").getStatus()).isEqualTo(400);
        assertThat(get(mvc, "/api/v1/seasons/abc/prices").getStatus()).isEqualTo(400);
    }

    @Test
    void 가격은_turnNo를_생략하면_0턴_풀_전체를_종목_번호_오름차순으로_준다() throws Exception {
        long id = start();
        JsonNode body = json(get(mvc, "/api/v1/seasons/" + id + "/prices"));
        assertThat(body.get("turnNo").asInt()).isZero();
        JsonNode prices = body.get("prices");
        assertThat(prices).hasSize(4);
        List<Long> assetIds = new ArrayList<>();
        prices.forEach(p -> {
            assetIds.add(p.get("assetId").asLong());
            assertThat(p.get("price").asString()).matches("^\\d+$");
        });
        assertThat(assetIds).isSorted();
        assertThat(json(get(mvc, "/api/v1/seasons/" + id + "/prices?turnNo=0"))).isEqualTo(body);
    }

    @Test
    void 아직_진행하지_않은_턴은_409_TURN_NOT_REACHED다() throws Exception {
        long id = start();
        MockHttpServletResponse r = get(mvc, "/api/v1/seasons/" + id + "/prices?turnNo=1");
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("TURN_NOT_REACHED");
        // 1턴 이상의 가격은 만들어지지 않았다(미래 가격 없음)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no >= 1",
                Long.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc", "1.5"})
    void turnNo가_음수이거나_숫자가_아니면_400이다(String turnNo) throws Exception {
        long id = start();
        MockHttpServletResponse r = get(mvc, "/api/v1/seasons/" + id + "/prices?turnNo=" + turnNo);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
    }
}
