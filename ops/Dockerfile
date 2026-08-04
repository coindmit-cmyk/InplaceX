FROM gradle:9.3.1-jdk21 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY InplaceX-bot-core InplaceX-bot-core
COPY InplaceX-auth-core InplaceX-auth-core
COPY InplaceX-ads-core InplaceX-ads-core
COPY InplaceX-logging InplaceX-logging
COPY InplaceX-test-support InplaceX-test-support
COPY InplaceX-backend InplaceX-backend
COPY InplaceX-identity InplaceX-identity
COPY InplaceX-android/app InplaceX-android/app

RUN ./gradlew --no-daemon :InplaceX-backend:installDist

FROM eclipse-temurin:11-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system inplacex \
    && useradd --system --gid inplacex --home-dir /app --shell /usr/sbin/nologin inplacex

WORKDIR /app

COPY --from=build --chown=inplacex:inplacex /workspace/InplaceX-backend/build/install/InplaceX-backend/ ./

USER inplacex

EXPOSE 8080

ENTRYPOINT ["/app/bin/InplaceX-backend"]
