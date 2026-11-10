package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SplitMix64Test {

    @Test
    void 참조_벡터와_부호_없는_값으로_같다() {
        // 표준 상수로 따로 계산한 값(시드 1234567)
        String[] expected = {"6457827717110365317", "3203168211198807973", "9817491932198370423",
                "4593380528125082431", "16408922859458223821"};
        SplitMix64 rng = new SplitMix64(1234567L);
        for (String e : expected) {
            assertThat(Long.toUnsignedString(rng.next())).isEqualTo(e);
        }
    }

    @Test
    void 거부_분기는_값을_버리고_다시_뽑는다() {
        // 시드 0, n = 2^30 + 1: 첫 31비트 값 1896895516 은 거부(그냥 % n 이면 823153691), next() 를 2번 소비한다.
        // 기대값은 저장소 밖 독립 구현으로 계산했다(M2 Python 이 똑같이 옮겨야 하는 요소).
        SplitMix64 rng = new SplitMix64(0L);
        assertThat(rng.nextInt(1073741825)).isEqualTo(926699317);
        assertThat(Long.toUnsignedString(rng.next())).isEqualTo("487617019471545679");
    }

    @Test
    void nextInt_1은_언제나_0() {
        SplitMix64 rng = new SplitMix64(7L);
        for (int i = 0; i < 100; i++) {
            assertThat(rng.nextInt(1)).isZero();
        }
    }

    @Test
    void nextInt_0과_음수는_거부한다() {
        SplitMix64 rng = new SplitMix64(7L);
        assertThatThrownBy(() -> rng.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rng.nextInt(-3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextInt_결과는_범위_안이고_모든_값이_나온다() {
        for (int n = 1; n <= 17; n++) {
            SplitMix64 rng = new SplitMix64(n);
            boolean[] seen = new boolean[n];
            for (int i = 0; i < 10_000; i++) {
                int v = rng.nextInt(n);
                assertThat(v).isBetween(0, n - 1);
                seen[v] = true;
            }
            for (boolean s : seen) {
                assertThat(s).isTrue();
            }
        }
    }

    @Test
    void 같은_시드는_같은_수열() {
        SplitMix64 a = new SplitMix64(-99L);
        SplitMix64 b = new SplitMix64(-99L);
        for (int i = 0; i < 50; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }
}
