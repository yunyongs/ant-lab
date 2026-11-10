# my Fund Manager API 이미지 (linux/arm64, Java 21). 배포 구조: docs/DEPLOY.md §4.1
# 빌드:  docker build -t antlab-api .      (compose 는 `docker compose build api`)
# 기본 실행 프로필은 이미지에 굽지 않는다 — compose 가 SPRING_PROFILES_ACTIVE=prod 로 준다.
# 기반 이미지는 정확한 패치 태그 + 검증한 digest 로 고정한다(2026-11-10 linux/arm64 확인).
# 빌드 컨텍스트는 .dockerignore 허용 목록이다. COPY 입력을 더하면 .dockerignore 의 허용 줄도 더한다.
# 올릴 때는 태그와 digest 를 함께 바꾸고 `docker buildx imagetools inspect <태그>` 로 arm64 를 확인한다.

# ---- 1단계: 빌드 (JDK) -------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS build
WORKDIR /workspace

# Wrapper 와 빌드 설정만 먼저 복사해 Gradle 배포판 내려받기를 소스 변경과 분리한다(캐시).
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version

# 소스는 src/main 만 복사한다. 시험(src/test)은 이미지 빌드 밖에서 돌린다(Docker 소켓을 빌드에 연결하지 않는다).
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar

# 의존성(lib)·loader(org)·메타데이터(META-INF)·앱 클래스(classes)를 레이어별로 복사할 수 있게 펼친다.
RUN mkdir /extracted \
    && cd /extracted \
    && jar xf /workspace/build/libs/app.jar

# ---- 2단계: 실행 (JRE) -------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

# 비루트 사용자(UID/GID 10001). 홈과 셸은 필요 없다.
RUN groupadd --system --gid 10001 antlab \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin antlab

WORKDIR /app

# 자주 바뀌는 것일수록 아래 레이어에 둔다(캐시 적중).
COPY --from=build /extracted/BOOT-INF/lib ./BOOT-INF/lib
COPY --from=build /extracted/org ./org
COPY --from=build /extracted/META-INF ./META-INF
COPY --from=build /extracted/BOOT-INF/classes ./BOOT-INF/classes
COPY --from=build /extracted/BOOT-INF/classpath.idx /extracted/BOOT-INF/layers.idx ./BOOT-INF/

USER 10001:10001
EXPOSE 8080

# exec 형식: Java 가 PID 1 이 되어 SIGTERM 을 직접 받는다(그레이스풀 셧다운).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
