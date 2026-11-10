package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;

/** Turn 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface TurnRepository extends AppendOnlyRepository<Turn, Long> {
}
