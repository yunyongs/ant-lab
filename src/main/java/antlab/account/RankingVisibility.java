package antlab.account;

import antlab.season.Season;

/**
 * Whether an asset is on a turn's ranking board, for the RANKING origin of an order. The board is computed, not stored, so the
 * answer must come from the same computation the ranking endpoint uses, on data up to that turn only, and be callable inside the
 * order transaction.
 */
public interface RankingVisibility {

    boolean onBoard(Season season, int turnNo, long assetId);
}
