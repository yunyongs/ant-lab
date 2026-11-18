package antlab.collect.ecos;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** The ECOS period codes the collector uses and how each one writes a date: D is yyyyMMdd, M is yyyyMM. */
public enum EcosCycle {
    D("D", "\\d{8}", DateTimeFormatter.BASIC_ISO_DATE),
    M("M", "\\d{6}", DateTimeFormatter.ofPattern("yyyyMM"));

    private final String code;
    private final Pattern shape;
    private final DateTimeFormatter format;

    EcosCycle(String code, String shape, DateTimeFormatter format) {
        this.code = code;
        this.shape = Pattern.compile(shape);
        this.format = format;
    }

    public String code() {
        return code;
    }

    /** The text of the request bounds and of the answer's TIME field. */
    public String write(LocalDate date) {
        return this == M ? String.format("%04d%02d", date.getYear(), date.getMonthValue()) : format.format(date);
    }

    boolean matches(String text) {
        return shape.matcher(text).matches();
    }

    /** A daily TIME is the day itself; a monthly TIME is the first day of that month (as the FRED monthly series are dated). */
    LocalDate parse(String text) {
        if (!matches(text)) {
            throw new IllegalArgumentException("not a " + code + " time");
        }
        try {
            return this == M ? LocalDate.of(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(4)), 1)
                    : LocalDate.parse(text, format);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a valid " + code + " time");
        }
    }
}
