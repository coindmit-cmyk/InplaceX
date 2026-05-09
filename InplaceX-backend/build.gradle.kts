plugins {
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
    testImplementation(libs.junit)
}
