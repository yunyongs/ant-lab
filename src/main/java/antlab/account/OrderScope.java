package antlab.account;

import antlab.season.Season;

/** The locked season and cash account an order is checked and applied under; only {@link OrderService#lock} makes one. */
public final class OrderScope {

    private final Season season;
    private final CashAccount account;
    private final int turnNo;

    OrderScope(Season season, CashAccount account, int turnNo) {
        this.season = season;
        this.account = account;
        this.turnNo = turnNo;
    }

    public Season season() {
        return season;
    }

    public CashAccount account() {
        return account;
    }

    public int turnNo() {
        return turnNo;
    }
}
