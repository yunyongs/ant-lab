package antlab.common.persistence;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

/** Detects a unique violation (SQLState 23505) of a named constraint from the cause chain, never from the detail line. */
public final class UniqueViolations {

    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueViolations() {
    }

    public static boolean isViolationOf(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && UNIQUE_VIOLATION.equals(cve.getSQLState())
                    && constraint.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState()) && sql.getMessage() != null
                    && sql.getMessage().toLowerCase().contains(constraint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
