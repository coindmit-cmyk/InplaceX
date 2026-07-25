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
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    testImplementation(project(":InplaceX-test-support"))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("com.mirkori.inplacex.backend.app.BackendApplicationKt")
}
