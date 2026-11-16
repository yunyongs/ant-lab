package antlab.agent;

/** Constants match the ck_agent_call_status list letter for letter; INVALID means the reply failed the schema. */
public enum AgentCallStatus {
    OK, UPSTREAM, TIMEOUT, LIMITED, POLICY_REFUSED, ERROR, INVALID
}
