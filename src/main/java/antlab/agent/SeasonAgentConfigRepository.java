package antlab.agent;

import antlab.common.persistence.AppendOnlyRepository;

/** SeasonAgentConfig append-only repository: insert and read by season id only. */
public interface SeasonAgentConfigRepository extends AppendOnlyRepository<SeasonAgentConfig, Long> {
}
