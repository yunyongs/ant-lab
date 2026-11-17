package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedCsvReaderTest {

    private static final List<String> HEADER = List.of("a", "b", "c");

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void reads_quotes_escaped_quotes_and_commas_inside_a_cell() {
        List<List<String>> rows = FeedCsvReader.read("a,b,c\n1,\"x,y\",\"say \"\"hi\"\"\"\n", HEADER);
        assertThat(rows).containsExactly(row("1", "x,y", "say \"hi\""));
    }

    @Test
    void an_unquoted_empty_cell_is_null_and_a_quoted_empty_cell_is_an_empty_string() {
        assertThat(FeedCsvReader.read("a,b,c\n1,,\"\"\n", HEADER)).containsExactly(row("1", null, ""));
    }

    @Test
    void lf_and_crlf_both_end_a_record_and_the_last_record_needs_no_line_end() {
        assertThat(FeedCsvReader.read("a,b,c\n1,2,3\n4,5,6", HEADER)).containsExactly(row("1", "2", "3"), row("4", "5", "6"));
        assertThat(FeedCsvReader.read("a,b,c\r\n1,2,3\r\n", HEADER)).containsExactly(row("1", "2", "3"));
    }

    @Test
    void a_quoted_cell_may_hold_a_line_break() {
        assertThat(FeedCsvReader.read("a,b,c\n1,\"x\ny\",3\n", HEADER)).containsExactly(row("1", "x\ny", "3"));
    }

    @Test
    void the_header_must_match_exactly() {
        assertThatThrownBy(() -> FeedCsvReader.read("A,b,c\n1,2,3\n", HEADER)).hasMessageContaining("header");
        assertThatThrownBy(() -> FeedCsvReader.read("b,a,c\n1,2,3\n", HEADER)).hasMessageContaining("header");
        assertThatThrownBy(() -> FeedCsvReader.read("a,b\n1,2\n", HEADER)).hasMessageContaining("header");
        assertThatThrownBy(() -> FeedCsvReader.read("", HEADER)).hasMessageContaining("header");
    }

    @Test
    void a_byte_order_mark_fails() {
        assertThatThrownBy(() -> FeedCsvReader.read("\uFEFFa,b,c\n1,2,3\n", HEADER)).hasMessageContaining("byte order mark");
    }

    @Test
    void a_quote_inside_an_unquoted_cell_and_a_bare_carriage_return_fail() {
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,속보 \"급등\" 소식,3\n", HEADER)).hasMessageContaining("quote inside");
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,x\ry,3\n", HEADER)).hasMessageContaining("bare carriage return");
    }

    @Test
    void a_trailing_blank_line_fails() {
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,2,3\n\n", HEADER)).hasMessageContaining("record 3");
    }

    @Test
    void a_short_or_long_record_fails() {
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,2\n", HEADER)).hasMessageContaining("2 cells");
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,2,3,4\n", HEADER)).hasMessageContaining("4 cells");
    }

    @Test
    void an_unclosed_quote_and_text_after_a_closing_quote_fail() {
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,\"2,3\n", HEADER)).hasMessageContaining("unclosed quote");
        assertThatThrownBy(() -> FeedCsvReader.read("a,b,c\n1,\"2\"x,3\n", HEADER)).hasMessageContaining("closing quote");
    }
}
