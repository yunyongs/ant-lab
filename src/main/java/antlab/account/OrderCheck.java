package antlab.account;

/** Result of {@link OrderService#check}: exactly one of a rejection (no state changed) or a plan to apply. */
public record OrderCheck(OrderRejection rejection, OrderPlan plan) {

    static OrderCheck rejected(OrderRejection rejection) {
        return new OrderCheck(rejection, null);
    }

    static OrderCheck passed(OrderPlan plan) {
        return new OrderCheck(null, plan);
    }
}
