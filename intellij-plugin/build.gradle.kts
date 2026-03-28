plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "com.oxidecode"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "261.*"
        }
    }
}

tasks {
    // Copy the compiled JVM native library into resources before building the jar.
    val copyNativeLib by registering(Copy::class) {
        val os = System.getProperty("os.name").lowercase()
        val ext = when {
            os.contains("win") -> "dll"
            os.contains("mac") -> "dylib"
            else -> "so"
        }
        val prefix = if (os.contains("win")) "" else "lib"
        from("${project.projectDir}/../target/release/${prefix}oxidecode_jvm.$ext")
        into("src/main/resources/native")
        rename { "oxidecode_jvm.$ext" }
    }

    processResources { dependsOn(copyNativeLib) }
}
