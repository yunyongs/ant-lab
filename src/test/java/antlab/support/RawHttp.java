package antlab.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Bytes on a socket, for requests {@code HttpClient} cannot send (it rejects {@code %ZZ} while building the URI). The
 * request is written once and never half-closed, so the server's own decision to close the connection stays visible.
 * Every wait is bounded (connect 2 s, read 5 s, 64 KB of answer) and a failed exchange keeps what was seen, so a test
 * failure shows the cause instead of a bare timeout.
 */
public final class RawHttp {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_ANSWER_BYTES = 64 * 1024;

    private RawHttp() {
    }

    /**
     * What came back. {@code closed} is true when the server ended the connection (EOF or reset); false when the read
     * timed out with the connection still open. {@code problem} names a write or read failure, else null.
     */
    public record Answer(int port, int sentBytes, String text, boolean closed, String problem) {

        /** The part before the blank line (status line and headers), or all of it when there is no blank line. */
        public String head() {
            int split = text.indexOf("\r\n\r\n");
            return split < 0 ? text : text.substring(0, split);
        }

        /** The part after the first blank line (empty when there is none). */
        public String body() {
            int split = text.indexOf("\r\n\r\n");
            return split < 0 ? "" : text.substring(split + 4);
        }

        /** The status of the first answer, or -1 when nothing parseable arrived. */
        public int status() {
            if (!text.startsWith("HTTP/1.1 ") || text.length() < 12) {
                return -1;
            }
            try {
                return Integer.parseInt(text.substring(9, 12));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** How many answers (status lines) the text holds. */
        public int answers() {
            int count = 0;
            for (int at = text.indexOf("HTTP/1.1 "); at >= 0; at = text.indexOf("HTTP/1.1 ", at + 1)) {
                count++;
            }
            return count;
        }

        /** All values of a header of the first answer, case-insensitive name. */
        public java.util.List<String> headerValues(String name) {
            java.util.List<String> values = new java.util.ArrayList<>();
            String[] lines = head().split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase(name)) {
                    values.add(lines[i].substring(colon + 1).trim());
                }
            }
            return values;
        }

        @Override
        public String toString() {
            String shown = text.length() > 600 ? text.substring(0, 600) + "…(" + text.length() + " chars)" : text;
            return "RawHttp[port=" + port + ", sent=" + sentBytes + " bytes, closed=" + closed + ", problem=" + problem
                    + ", answer=" + shown.replace("\r\n", "\\r\\n") + "]";
        }
    }

    /** Sends the text (ISO-8859-1 bytes: every char is one byte) and reads until the server closes or 5 s pass. */
    public static Answer send(int port, String request) {
        return sendBytes(port, request.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static Answer sendBytes(int port, byte[] request) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        boolean closed = false;
        String problem = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            try {
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
            } catch (IOException e) {
                // the server may close while the rest is still being sent; what it answered is still readable
                problem = "write: " + e;
            }
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            try {
                int count;
                while (received.size() < MAX_ANSWER_BYTES && (count = in.read(chunk)) >= 0) {
                    received.write(chunk, 0, count);
                }
                closed = received.size() < MAX_ANSWER_BYTES;
            } catch (SocketTimeoutException e) {
                problem = problem == null ? "read timed out after " + READ_TIMEOUT_MS + " ms" : problem;
            } catch (IOException e) {
                closed = true;
                problem = problem == null ? "read: " + e : problem;
            }
        } catch (IOException e) {
            problem = "connect: " + e;
        }
        return new Answer(port, request.length, received.toString(StandardCharsets.UTF_8), closed, problem);
    }
}
