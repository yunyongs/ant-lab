package antlab.season;

/** 시즌 시드의 출처. 운영은 보안 난수, 시험은 고정값을 끼운다. 시드는 시즌이 끝나기 전 응답·로그에 내지 않는다. */
public interface SeedGenerator {

    long nextSeed();
}
