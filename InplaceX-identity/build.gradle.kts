plugins {
    application
    id("org.jetbrains.kotlin.jvm")
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
    implementation(project(":InplaceX-backend"))
    implementation(project(":InplaceX-logging"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    testImplementation(libs.junit)
}

application {
    mainClass.set("com.mirkori.inplacex.identity.app.IdentityApplicationKt")
}
