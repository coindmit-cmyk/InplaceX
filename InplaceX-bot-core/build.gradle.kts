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
    testImplementation(project(":InplaceX-logging"))
    testImplementation(project(":InplaceX-test-support"))
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runBotBenchmarkMatrix") {
    group = "verification"
    description = "Runs a manual bot benchmark matrix and writes a text report."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.mirkori.inplacex.core.bot.BotBenchmarkMatrixRunnerKt")
}

tasks.register<JavaExec>("runBotBenchmarkSummary") {
    group = "verification"
    description = "Runs aggregated bot benchmarks with average stats per difficulty and code length."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.mirkori.inplacex.core.bot.BotBenchmarkSummaryRunnerKt")
}

tasks.register<JavaExec>("runBotEasyFailureTrace") {
    group = "verification"
    description = "Finds and prints a detailed EASY len=4 benchmark failure trace."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.mirkori.inplacex.core.bot.BotEasyFailureTraceRunnerKt")
}
