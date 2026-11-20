package antlab.agent;

import java.io.Serializable;

/** Key of {@link PersonaVersion}: the persona and its released version. */
public record PersonaVersionId(String personaKey, int version) implements Serializable {
}
