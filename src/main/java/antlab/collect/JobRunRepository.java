package antlab.collect;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Read-only lookup for the run log. Extends {@code Repository} only, so there is no save or delete (SPRING_JPA §4.4). */
public interface JobRunRepository extends Repository<JobRun, Long> {

    Optional<JobRun> findById(Long id);

    Optional<JobRun> findFirstByJobNameOrderByStartedAtDesc(JobName jobName);
}
