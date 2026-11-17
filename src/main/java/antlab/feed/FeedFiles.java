package antlab.feed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the five CSV files come from. Production reads the classpath copy of {@code feed/}; tests may read a directory. */
@FunctionalInterface
interface FeedFiles {

    byte[] read(String fileName) throws IOException;

    /** The copy of {@code feed/*.csv} that the build puts on the classpath. The working directory is never consulted. */
    static FeedFiles classpath(ClassLoader loader) {
        return fileName -> {
            try (InputStream in = loader.getResourceAsStream("feed/" + fileName)) {
                if (in == null) {
                    throw new IOException("feed/" + fileName + " is not on the classpath");
                }
                return in.readAllBytes();
            }
        };
    }

    static FeedFiles directory(Path dir) {
        return fileName -> Files.readAllBytes(dir.resolve(fileName));
    }

    /** Strict UTF-8: malformed input is an error, not a replacement character. */
    static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
