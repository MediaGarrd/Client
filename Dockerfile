FROM gradle:9.4.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :bootJar --no-daemon --stacktrace --info --warning-mode all

FROM eclipse-temurin:21.0.13_9-jre
WORKDIR /app
RUN set -eux; \
    if getent passwd 1000 >/dev/null; then userdel "$(getent passwd 1000 | cut -d: -f1)"; fi; \
    if getent group 1000 >/dev/null; then groupdel "$(getent group 1000 | cut -d: -f1)"; fi; \
    groupadd --gid 1000 mediagarrd; \
    useradd --uid 1000 --gid 1000 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin mediagarrd; \
    mkdir -p /var/lib/mediagarrd/client /var/lib/mediagarrd/downloads; \
    chown -R 1000:1000 /var/lib/mediagarrd
COPY --from=build /workspace/build/libs/*.jar app.jar
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/mediagarrd-entrypoint
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["sh", "-c", "curl -fsS -o /dev/null \"http://127.0.0.1:${CLIENT_PORT:-8081}/api/auth/status\""]
ENTRYPOINT ["/usr/local/bin/mediagarrd-entrypoint"]
CMD ["java", "-jar", "/app/app.jar"]
