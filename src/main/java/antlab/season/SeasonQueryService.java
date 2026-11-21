package antlab.season;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.league.LeagueEntry;
import antlab.league.LeagueEntryRepository;
import antlab.member.CurrentMember;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시즌 조회 — 읽기 전용. 회원을 만들지 않고, 다른 회원의 시즌은 없는 것과 같게 404 로 답한다. */
@Service
public class SeasonQueryService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final TurnPriceRepository turnPrices;
    private final MemberRepository members;
    private final LeagueEntryRepository leagueEntries;
    private final Clock clock;

    public SeasonQueryService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            TurnPriceRepository turnPrices, MemberRepository members, LeagueEntryRepository leagueEntries,
            Clock clock) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.turnPrices = turnPrices;
        this.members = members;
        this.leagueEntries = leagueEntries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeasonListDto list() {
        Optional<Long> memberId = currentMember.existingId();
        if (memberId.isEmpty()) {
            return new SeasonListDto(List.of());
        }
        MemberKind kind = kindOf(memberId.get());
        Instant now = clock.instant();
        return new SeasonListDto(seasons.findWithBalanceByMemberId(memberId.get()).stream()
                .map(r -> SeasonDto.from(r.season(), r.balance(), kind, now, r.leagueId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public SeasonDto get(Long seasonId) {
        Season season = find(seasonId);
        CashAccount account = accounts.findById(season.getId())
                .orElseThrow(() -> new IllegalStateException("시즌에 현금 계좌가 없다"));
        return SeasonDto.from(season, account.getBalance(), kindOf(season.getMemberId()), clock.instant(),
                leagueEntries.findBySeasonId(season.getId()).map(LeagueEntry::getLeagueId).orElse(null));
    }

    /** One read per request, never per row: the kind decides the derived notStarted flag. */
    private MemberKind kindOf(long memberId) {
        return members.existsByIdAndKind(memberId, MemberKind.AGENT) ? MemberKind.AGENT : MemberKind.HUMAN;
    }

    /** turnNo 를 생략하면 현재 턴. 아직 진행하지 않은 턴은 409. */
    @Transactional(readOnly = true)
    public TurnPricesDto prices(Long seasonId, Integer turnNo) {
        Season season = find(seasonId);
        int turn = turnNo == null ? season.getCurrentTurnNo() : turnNo;
        if (turn > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
        }
        return new TurnPricesDto(turn, turnPrices.findBySeasonIdAndTurnNoOrderByAssetIdAsc(season.getId(), turn)
                .stream().map(TurnPriceDto::from).toList());
    }

    private Season find(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        return seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
    }
}
