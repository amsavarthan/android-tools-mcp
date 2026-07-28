import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
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

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("261.*")
    }
    buildSearchableOptions {
        enabled = false
    }
}
