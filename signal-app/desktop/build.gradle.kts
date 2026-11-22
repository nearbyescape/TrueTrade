import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// Windows installers need MAJOR.MINOR.BUILD with each part small enough: build number from CI.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt().coerceIn(1, 65_000)

compose.desktop {
    application {
        mainClass = "com.signalbot.desk.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TrueTrade Desk"
            packageVersion = "1.0.$buildNumber"
            description = "Break and retest signals with live futures charts"
            vendor = "TrueTrade"
            copyright = "Personal use"
            // Everything the app might touch at runtime (WebSockets, TLS ciphers) is inside the installer.
            includeAllModules = true
            windows {
                menu = true
                menuGroup = "TrueTrade"
                shortcut = true
                dirChooser = true
                perUserInstall = true // installs without admin rights
                upgradeUuid = "8f3c2a71-5d4e-4b8a-9c61-2e7f0b9d4a13"
                iconFile.set(project.file("icon.ico"))
            }
            linux { iconFile.set(project.file("icon.png")) }
        }
    }
}

// ./gradlew :desktop:renderScreens  -> desktop/build/screens/*.png (design review without a screen)
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.signalbot.desk.RenderScreensKt")
    jvmArgs("-Djava.awt.headless=true")
    workingDir = projectDir
}
