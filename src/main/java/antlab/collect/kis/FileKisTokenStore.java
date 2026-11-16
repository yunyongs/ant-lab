package antlab.collect.kis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token file shared with tools/fetch: {@code token_real.json} with {@code access_token} and {@code expires_at}
 * (epoch seconds). Written to a private temp file (mode 600) and moved into place atomically, so a reader never sees a
 * half-written file and the token is never world-readable.
 */
final class FileKisTokenStore implements KisTokenStore {

    static final String FILE_NAME = "token_real.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;

    FileKisTokenStore(Path directory) {
        this.directory = directory;
    }

    Path file() {
        return directory.resolve(FILE_NAME);
    }

    @Override
    public Optional<StoredToken> load() {
        try {
            JsonNode node = JSON.readTree(Files.readString(file(), StandardCharsets.UTF_8));
            String token = node.path("access_token").asString("");
            JsonNode expiresAt = node.path("expires_at");
            if (token.isEmpty() || !expiresAt.isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new StoredToken(token, expiresAt.decimalValue().longValue()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(StoredToken token) {
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "token_real", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(temp, JSON.writeValueAsString(java.util.Map.of(
                        "access_token", token.accessToken(), "expires_at", token.expiresAtEpochSecond())), StandardCharsets.UTF_8);
                Files.move(temp, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new KisException("KIS_HTTP", "could not write the KIS token cache file (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public void discard(String rejectedAccessToken) {
        load().filter(stored -> stored.accessToken().equals(rejectedAccessToken)).ifPresent(stored -> {
            try {
                Files.deleteIfExists(file());
            } catch (IOException e) {
                throw new KisException("KIS_HTTP", "could not remove the rejected KIS token cache file (" + e.getClass().getSimpleName() + ")");
            }
        });
    }
}
