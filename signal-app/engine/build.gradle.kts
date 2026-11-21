import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Only runtime dependency. JSON is parsed as a tree, so no serialization compiler plugin is needed.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// ./gradlew :engine:verify --args="BTC 1h"
// Prints the latest indicator values so you can compare them with TradingView.
tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Fetch live candles and print indicator values to compare with TradingView."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.signalbot.engine.tools.VerifyKt")
}

// ./gradlew :engine:backtest --args="2023-01 2026-11-20"   (first month, last day)
// Downloads Binance futures history (cached in backtest-out/cache) and backtests the strategy.
tasks.register<JavaExec>("backtest") {
    group = "verification"
    description = "Backtest the strategy on real Binance futures history."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.signalbot.engine.tools.RunBacktestKt")
    maxHeapSize = "3g"
}

// ./gradlew :engine:scan --args="100000 1 2"   (capital, risk % per trade, leverage)
// Runs the signal engine live on BTC, ETH, SOL, GOLD and SILVER futures.
tasks.register<JavaExec>("scan") {
    group = "application"
    description = "Run the signal engine on live futures data."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.signalbot.engine.tools.ScanKt")
}
