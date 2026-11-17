package antlab.agent.decision;

import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** One model call (an attempt) of a turn, as the runner made it. Texts are stored as sent and received. */
public record CallRecordRequest(
        @Min(1) @Max(32767)
        int attemptNo,
        @NotNull
        AgentCallKind kind,
        UUID ledgerCallId,
        @NotNull
        AgentCallStatus status,
        @Min(100) @Max(599)
        Integer httpStatus,
        @NotBlank @Size(max = 200)
        String modelRequested,
        @Size(max = 200)
        String modelActual,
        @Size(max = 100)
        String provider,
        @NotNull
        String requestText,
        String responseText,
        @Min(0)
        Integer tokensIn,
        @Min(0)
        Integer tokensOut,
        @DecimalMin("0") @Digits(integer = 11, fraction = 8)
        BigDecimal costUsd,
        @Min(0)
        int latencyMs) {
}
