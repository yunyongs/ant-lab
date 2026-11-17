package antlab.account;

import antlab.season.Season;
import org.springframework.stereotype.Component;

/**
 * Default {@link RankingVisibility} until a ranking board exists: nothing is on a board, so a RANKING origin is always
 * refused. The board feature replaces this bean.
 */
@Component
class NoRankingBoard implements RankingVisibility {

    @Override
    public boolean onBoard(Season season, int turnNo, long assetId) {
        return false;
    }
}
