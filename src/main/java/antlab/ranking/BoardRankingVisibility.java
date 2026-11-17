package antlab.ranking;

import antlab.account.RankingVisibility;
import antlab.season.Season;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link RankingVisibility} backed by the ranking board: an asset is on the board of a turn when it appears in one of the five asset
 * lists (group lists name groups, not assets, so they open no order route). It runs the same calculation on the same stored rows as
 * {@link RankingQueryService}, for the asked turn only, so the answer is what the player could see at that turn and nothing later.
 * It reads without locking and writes nothing, so it can run inside the order transaction (four statements per call). Damaged price
 * history (a pool asset without a price) throws instead of answering false: the order or agent decision fails as a broken state.
 */
@Component
class BoardRankingVisibility implements RankingVisibility {

    private final RankingInputReader reader;

    BoardRankingVisibility(RankingInputReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean onBoard(Season season, int turnNo, long assetId) {
        // There is no board before the first played turn (the endpoint refuses turn 0 too), and only the current turn's board counts.
        if (turnNo < 1 || turnNo != season.getCurrentTurnNo()) {
            return false;
        }
        return assetIds(RankingBoardCalculator.board(reader.read(season.getId(), turnNo))).contains(assetId);
    }

    /** Every asset id that has a row on any of the five asset lists. */
    static Set<Long> assetIds(RankingBoard board) {
        Set<Long> ids = new HashSet<>();
        board.gainers().forEach(r -> ids.add(r.assetId()));
        board.losers().forEach(r -> ids.add(r.assetId()));
        board.attention().forEach(r -> ids.add(r.assetId()));
        board.risingStreaks().forEach(r -> ids.add(r.assetId()));
        board.fallingStreaks().forEach(r -> ids.add(r.assetId()));
        return ids;
    }
}
