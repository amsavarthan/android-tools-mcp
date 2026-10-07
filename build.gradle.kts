import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

version = providers.gradleProperty("plugin.version").get()

val localProps = Properties().apply {
    val f = file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val androidStudioPath: String =
    localProps.getProperty("android.studio.path")
        ?: System.getenv("ANDROID_STUDIO_PATH")
        ?: "/Applications/Android Studio.app"

dependencies {
    intellijPlatform {
        local(androidStudioPath)
        bundledPlugin("com.google.tools.ij.aiplugin")
    }
}

// Build 262's Gemini plugin ships Java 25 bytecode, so compile on a JDK 25 toolchain but emit
// Java 21 bytecode against the Java 21 API so the plugin still loads on 253–261.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-Xjdk-release=21")
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    // Set per task: the IntelliJ Platform plugin overrides the extension-level jvmTarget with the
    // target platform's Java version (25 for build 262).
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("262.*")
    }
    buildSearchableOptions {
        enabled = false
    }
}
