plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

group = "com.mirkori.platform"
version = "0.4.4-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            artifactId = "platform-game-sdk"
        }
    }
}

tasks.test {
    useJUnit()
}
