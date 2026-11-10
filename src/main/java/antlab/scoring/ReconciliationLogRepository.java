package antlab.scoring;

import antlab.common.persistence.AppendOnlyRepository;

/** 대사 기록 추가 전용 저장소 — 넣기와 번호로 읽기만. 대사 쿼리는 P0-32 가 더한다. */
public interface ReconciliationLogRepository extends AppendOnlyRepository<ReconciliationLog, Long> {
}
