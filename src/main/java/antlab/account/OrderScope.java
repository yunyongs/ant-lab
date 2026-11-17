package antlab.account;

import antlab.season.Season;

/** The locked season and cash account an order is checked and applied under; made by {@link OrderService#lock}. */
public record OrderScope(Season season, CashAccount account, int turnNo) {
}
