plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

group = "com.mirkori.platform"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
