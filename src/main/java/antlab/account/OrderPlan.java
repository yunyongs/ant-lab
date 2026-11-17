package antlab.account;

import antlab.season.Lineup;
import java.math.BigDecimal;

/**
 * An order that passed every check and is ready to be written. Only {@link OrderService#check} makes one, and it is
 * valid only until the next write of the same season (the next unit changes holdings, cash and the last formation change).
 */
public final class OrderPlan {

    private final OrderScope scope;
    private final PlaceOrderRequest request;
    private final BigDecimal price;
    private final Long changeId;
    private final Holding holding;
    private final Lineup open;
    private final BigDecimal notional;
    private final BigDecimal fee;
    private final BigDecimal pnl;

    OrderPlan(OrderScope scope, PlaceOrderRequest request, BigDecimal price, Long changeId, Holding holding,
            Lineup open, BigDecimal notional, BigDecimal fee, BigDecimal pnl) {
        this.scope = scope;
        this.request = request;
        this.price = price;
        this.changeId = changeId;
        this.holding = holding;
        this.open = open;
        this.notional = notional;
        this.fee = fee;
        this.pnl = pnl;
    }

    OrderScope scope() {
        return scope;
    }

    PlaceOrderRequest request() {
        return request;
    }

    BigDecimal price() {
        return price;
    }

    Long changeId() {
        return changeId;
    }

    Holding holding() {
        return holding;
    }

    Lineup open() {
        return open;
    }

    BigDecimal notional() {
        return notional;
    }

    BigDecimal fee() {
        return fee;
    }

    BigDecimal pnl() {
        return pnl;
    }
}
