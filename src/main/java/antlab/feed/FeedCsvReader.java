package antlab.feed;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader for the files in {@code feed/}, with the strictness of PostgreSQL {@code \copy ... HEADER MATCH}: the header must equal
 * the expected names exactly (case, order, no BOM), every record must have the header's column count (a trailing blank line fails), an
 * unquoted empty cell is NULL ({@code null}) and a quoted empty cell is an empty string. LF and CRLF both end a record.
 */
final class FeedCsvReader {

    private FeedCsvReader() {
    }

    /** Data rows without the header row. */
    static List<List<String>> read(String text, List<String> expectedHeader) {
        if (text.startsWith("\uFEFF")) {
            throw new IllegalStateException("CSV: byte order mark");
        }
        List<List<String>> records = parse(text);
        if (records.isEmpty() || !records.get(0).equals(expectedHeader)) {
            throw new IllegalStateException("CSV: header does not match " + expectedHeader);
        }
        for (int i = 1; i < records.size(); i++) {
            if (records.get(i).size() != expectedHeader.size()) {
                throw new IllegalStateException("CSV: record " + (i + 1) + " has " + records.get(i).size() + " cells, expected "
                        + expectedHeader.size());
            }
        }
        return records.subList(1, records.size());
    }

    private static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        boolean pending = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"' && cell.length() == 0 && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
                pending = true;
            } else if (ch == ',') {
                row.add(cellValue(cell, wasQuoted));
                cell.setLength(0);
                wasQuoted = false;
                pending = true;
            } else if (ch == '\n' || (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n')) {
                if (ch == '\r') {
                    i++;
                }
                row.add(cellValue(cell, wasQuoted));
                records.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
                wasQuoted = false;
                pending = false;
            } else {
                if (wasQuoted) {
                    throw new IllegalStateException("CSV: text after a closing quote");
                }
                if (ch == '"') {
                    throw new IllegalStateException("CSV: quote inside an unquoted cell");
                }
                if (ch == '\r') {
                    throw new IllegalStateException("CSV: bare carriage return");
                }
                cell.append(ch);
                pending = true;
            }
        }
        if (quoted) {
            throw new IllegalStateException("CSV: unclosed quote");
        }
        if (pending) {
            row.add(cellValue(cell, wasQuoted));
            records.add(row);
        }
        return records;
    }

    private static String cellValue(StringBuilder cell, boolean wasQuoted) {
        return cell.length() == 0 && !wasQuoted ? null : cell.toString();
    }
}
