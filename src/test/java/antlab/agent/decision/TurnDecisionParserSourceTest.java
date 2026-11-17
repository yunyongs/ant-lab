package antlab.agent.decision;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the shape check keeps of {@code source}: the kind and {@code feed_seq}; {@code ranking_rank} is checked, not kept. */
class TurnDecisionParserSourceTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static JsonNode reply(String source) {
        return MAPPER.readTree("""
                {"schema":"turn-decision/1","formation_change":null,"theses":[],"reason":"r",
                 "orders":[{"asset":"KR:000660","side":"BUY","quantity":1,"position":"MIDFIELDER","source":%s}]}"""
                .formatted(source));
    }

    private static TurnDecision.OrderProposal only(String source) {
        TurnDecisionParser.Result result = TurnDecisionParser.parse(reply(source));
        assertThat(result.valid()).as(result.violations().toString()).isTrue();
        return result.decision().orders().get(0);
    }

    @Test
    void the_feed_seq_of_the_reply_is_kept_on_the_order() {
        TurnDecision.OrderProposal order = only("{\"kind\":\"FEED\",\"feed_seq\":7}");

        assertThat(order.source()).isEqualTo(OrderSource.FEED);
        assertThat(order.feedSeq()).isEqualTo(7);
    }

    @Test
    void a_missing_feed_seq_stays_null_and_is_not_a_shape_error() {
        TurnDecision.OrderProposal order = only("{\"kind\":\"ALERT\"}");

        assertThat(order.source()).isEqualTo(OrderSource.ALERT);
        assertThat(order.feedSeq()).isNull();
    }

    @Test
    void ranking_rank_is_validated_but_not_kept() {
        TurnDecision.OrderProposal order = only("{\"kind\":\"RANKING\",\"ranking_rank\":2}");

        assertThat(order.source()).isEqualTo(OrderSource.RANKING);
        assertThat(order.feedSeq()).isNull();
    }

    @Test
    void a_feed_seq_that_is_not_an_integer_is_still_a_shape_error() {
        TurnDecisionParser.Result result = TurnDecisionParser.parse(reply("{\"kind\":\"FEED\",\"feed_seq\":\"7\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> {
            assertThat(v.pointer()).isEqualTo("/orders/0/source/feed_seq");
            assertThat(v.keyword()).isEqualTo("type");
        });
    }
}
