package antlab.support;

import antlab.engine.GeneratedPath;
import antlab.engine.PathRequest;
import antlab.engine.PathSource;

/**
 * 골든 시험용 위임 경로 출처. 골든의 달 목록은 SQL 하네스를 돌린 뒤에야 알 수 있어 일반 빈으로는 시점이 맞지 않는다.
 * 끼우기 전에 부르면 실패한다(우연히 운영 경로가 쓰이는 일이 없게). 운영 코드에는 두지 않는다.
 */
public final class DeferredPathSource implements PathSource {

    private volatile PathSource delegate;

    public void set(PathSource source) {
        this.delegate = source;
    }

    @Override
    public GeneratedPath generate(PathRequest request) {
        PathSource current = delegate;
        if (current == null) {
            throw new IllegalStateException("골든 경로 출처가 아직 정해지지 않았다");
        }
        return current.generate(request);
    }
}
