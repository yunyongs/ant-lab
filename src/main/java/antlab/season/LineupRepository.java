package antlab.season;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 라인업 저장소 — 가변 엔티티라 넣기와 읽기. 삭제는 없다. 열린 구간 조회 등은 쓰는 기능이 더한다. */
public interface LineupRepository extends Repository<Lineup, Long> {

    Lineup save(Lineup lineup);

    Optional<Lineup> findById(Long id);
}
