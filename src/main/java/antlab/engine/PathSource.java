package antlab.engine;

/** 시즌 경로의 출처. 운영 = 단순 부트스트랩, 골든 시험 = SQL 시즌의 고정 경로. */
public interface PathSource {

    /** 길이 정책 위반은 DomainException(INVALID_ARGUMENT | PATH_TOO_LONG). 결과는 불변이다. */
    GeneratedPath generate(PathRequest request);
}
