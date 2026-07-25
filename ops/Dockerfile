FROM gradle:9.3.1-jdk21 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY InplaceX-bot-core InplaceX-bot-core
COPY InplaceX-logging InplaceX-logging
COPY InplaceX-test-support InplaceX-test-support
COPY InplaceX-backend InplaceX-backend
COPY InplaceX-android/app InplaceX-android/app

RUN ./gradlew --no-daemon :InplaceX-backend:installDist

FROM eclipse-temurin:11-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /workspace/InplaceX-backend/build/install/InplaceX-backend/ ./

EXPOSE 8080

ENTRYPOINT ["/app/bin/InplaceX-backend"]
