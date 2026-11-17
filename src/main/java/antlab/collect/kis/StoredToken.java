package antlab.collect.kis;

/** An access token and its expiry (epoch seconds). The token never appears in {@code toString}. */
record StoredToken(String accessToken, long expiresAtEpochSecond) {

    @Override
    public String toString() {
        return "StoredToken[" + SecretMasker.MASK + ", expiresAt=" + expiresAtEpochSecond + "]";
    }
}
