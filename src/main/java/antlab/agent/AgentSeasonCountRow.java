package antlab.agent;

/** Season counts of one agent from one grouped query; the query splits a stored PAUSED by the 7-day cutoff. */
public record AgentSeasonCountRow(Long agentId, long started, long counted, long voided, long unfinishedAbandon,
        long inProgress, long paused, long halted) {
}
