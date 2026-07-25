plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

group = "com.mirkori.inplacex"
version = "1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":InplaceX-bot-core"))
    implementation(project(":InplaceX-logging"))
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.postgresql:postgresql:42.7.4")

    testImplementation("com.h2database:h2:2.3.232")
    testImplementation(project(":InplaceX-test-support"))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation("org.testcontainers:postgresql:1.21.4")
}

application {
    mainClass.set("com.mirkori.inplacex.backend.app.BackendApplicationKt")
}
