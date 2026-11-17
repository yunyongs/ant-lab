package antlab.account;

/** Result of {@link OrderService#check}: exactly one of a rejection (no state changed) or a plan to apply. */
public final class OrderCheck {

    private final OrderRejection rejection;
    private final OrderPlan plan;

    private OrderCheck(OrderRejection rejection, OrderPlan plan) {
        this.rejection = rejection;
        this.plan = plan;
    }

    static OrderCheck rejected(OrderRejection rejection) {
        return new OrderCheck(rejection, null);
    }

    static OrderCheck passed(OrderPlan plan) {
        return new OrderCheck(null, plan);
    }

    public OrderRejection rejection() {
        return rejection;
    }

    public OrderPlan plan() {
        return plan;
    }
}
