# 서비스 6개가 함께 쓰는 이미지. 빌드 컨텍스트는 레포 루트이고, 먼저 ./gradlew bootJar 로 jar를 만든다.
#   docker build --platform linux/arm64 -f deploy/Dockerfile --build-arg SERVICE=article -t board-perf/article:<tag> .
FROM eclipse-temurin:17-jre AS extract
ARG SERVICE
WORKDIR /build
COPY service/${SERVICE}/build/libs/ libs/
# 실행 jar를 계층별로 풀어 의존성 계층을 이미지 캐시로 재사용한다 (-plain.jar는 실행할 수 없는 jar라 뺀다)
RUN jar="$(find libs -name '*.jar' ! -name '*-plain.jar')" \
    && java -Djarmode=tools -jar "${jar}" extract --layers --launcher --destination extracted

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
COPY deploy/entrypoint.sh /app/entrypoint.sh
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
