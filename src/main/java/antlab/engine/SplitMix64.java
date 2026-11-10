package antlab.engine;

/**
 * SplitMix64 난수 — 정수 연산만 쓴다. 상태는 64비트 {@code long} 하나이고, 곱셈·덧셈의 넘침은 2^64 나머지로 그대로 맞다.
 * 같은 시드는 언제나 같은 수열을 낸다(시즌 경로 재현의 바탕). 식을 바꾸면 엔진 판을 올려야 한다(SimpleBootstrap).
 * Python 이식은 {@code & 0xFFFFFFFFFFFFFFFF} 마스크와 {@code >>}(부호 없는 값)로 같은 식이 된다.
 */
final class SplitMix64 {

    private long state;

    SplitMix64(long seed) {
        this.state = seed;
    }

    /** 다음 64비트 값(부호 있는 long 의 비트 그대로 — 부호 없는 값으로 읽으려면 Long.toUnsignedString). */
    long next() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0, n) 균등, 1 ≤ n < 2^31. 나머지 편향은 거부 표본으로 없앤다(마지막 불완전 묶음이면 다시 뽑는다). */
    int nextInt(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n 은 1 이상이어야 한다");
        }
        while (true) {
            long r = next() >>> 33; // 상위 31비트, 0 ≤ r < 2^31
            long v = r % n;
            if (r - v + (n - 1) < (1L << 31)) {
                return (int) v;
            }
        }
    }
}
