FROM gradle:9.3.1-jdk21@sha256:f3784cc59d7fbab1e0ddb09c4cd082f13e16d3fb8c50b7922b7aeae8e9507da5 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY InplaceX-bot-core InplaceX-bot-core
COPY InplaceX-auth-core InplaceX-auth-core
COPY InplaceX-ads-core InplaceX-ads-core
COPY InplaceX-logging InplaceX-logging
COPY InplaceX-test-support InplaceX-test-support
COPY Mirkori-platform-game-sdk Mirkori-platform-game-sdk
COPY InplaceX-backend InplaceX-backend
COPY InplaceX-identity InplaceX-identity
COPY InplaceX-android/version.properties InplaceX-android/version.properties
COPY InplaceX-android/app InplaceX-android/app

RUN ./gradlew --no-daemon :InplaceX-backend:installDist

FROM eclipse-temurin:11-jre-jammy@sha256:e8acde9cc75b96765f005857cfeb7f826409177482c3f70400d5a94328689d56

ARG INPLACEX_BUILD_VERSION
ARG INPLACEX_BUILD_REVISION
ARG INPLACEX_SOURCE_ARCHIVE_SHA256

RUN case "$INPLACEX_BUILD_VERSION" in ''|*[!A-Za-z0-9._-]* ) exit 65 ;; esac \
    && case "$INPLACEX_BUILD_REVISION" in *[!0-9a-f]*|'') exit 65 ;; esac \
    && test "${#INPLACEX_BUILD_REVISION}" -eq 40 \
    && case "$INPLACEX_SOURCE_ARCHIVE_SHA256" in *[!0-9a-f]*|'') exit 65 ;; esac \
    && test "${#INPLACEX_SOURCE_ARCHIVE_SHA256}" -eq 64

LABEL org.opencontainers.image.title="InplaceX online backend" \
      org.opencontainers.image.version="$INPLACEX_BUILD_VERSION" \
      org.opencontainers.image.revision="$INPLACEX_BUILD_REVISION" \
      org.opencontainers.image.source="https://github.com/coindmit-cmyk/InplaceX" \
      com.mirkori.inplacex.source-archive-sha256="$INPLACEX_SOURCE_ARCHIVE_SHA256"

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system inplacex \
    && useradd --system --gid inplacex --home-dir /app --shell /usr/sbin/nologin inplacex

WORKDIR /app

COPY --from=build --chown=inplacex:inplacex /workspace/InplaceX-backend/build/install/InplaceX-backend/ ./

USER inplacex

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=4 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/ready >/dev/null || exit 1

ENTRYPOINT ["/app/bin/InplaceX-backend"]
