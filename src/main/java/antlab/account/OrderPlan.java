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
    private final BigDecimal rate;
    private final BigDecimal fxCost;
    private final BigDecimal pnl;
    private final OrderSource source;
    private final Long turnFeedId;

    OrderPlan(OrderScope scope, PlaceOrderRequest request, BigDecimal price, Long changeId, Holding holding,
            Lineup open, BigDecimal notional, BigDecimal fee, BigDecimal rate, BigDecimal fxCost, BigDecimal pnl,
            OrderSource source, Long turnFeedId) {
        this.scope = scope;
        this.request = request;
        this.price = price;
        this.changeId = changeId;
        this.holding = holding;
        this.open = open;
        this.notional = notional;
        this.fee = fee;
        this.rate = rate;
        this.fxCost = fxCost;
        this.pnl = pnl;
        this.source = source;
        this.turnFeedId = turnFeedId;
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

    /** The combined rate the fee was computed with (KR rate, or US rate + FX rate). */
    BigDecimal rate() {
        return rate;
    }

    /** The FX part of the fee for a US asset (0 for KR), for display only; the fill writes {@link #fee} alone. */
    BigDecimal fxCost() {
        return fxCost;
    }

    BigDecimal pnl() {
        return pnl;
    }

    /** The verified origin that is written to {@code orders.source}. */
    OrderSource source() {
        return source;
    }

    /** Internal id of the feed item the origin points at; null unless the origin is FEED or ALERT. */
    Long turnFeedId() {
        return turnFeedId;
    }
}
