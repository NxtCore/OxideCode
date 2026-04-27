plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "com.oxidecode"
version = "0.4.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.1")
        bundledPlugins("Git4Idea")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.aayushatharva.brotli4j:brotli4j:1.16.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "261.*"
        }
    }
}

tasks {
    val nativePlatforms = listOf(
        Triple("win32", "x64", "dll") to Pair("", "oxidecode_jvm_x64.dll"),
        Triple("darwin", "arm64", "dylib") to Pair("lib", "oxidecode_jvm_arm64.dylib"),
        Triple("darwin", "x64", "dylib") to Pair("lib", "oxidecode_jvm_x64.dylib"),
        Triple("linux", "x64", "so") to Pair("lib", "oxidecode_jvm_x64.so"),
    )

    val copyNativeLibs by registering(Copy::class) {
        nativePlatforms.forEach { (platform, mapping) ->
            val (_, _, ext) = platform
            val (prefix, destName) = mapping
            from("${project.projectDir}/../target/release/${prefix}oxidecode_jvm.$ext") {
                rename { destName }
            }
        }
        into("${project.projectDir}/src/main/resources/native")
        outputs.upToDateWhen { false }
    }

    val copyNativeLib by registering(Copy::class) {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val ext = when {
            os.contains("win") -> "dll"
            os.contains("mac") -> "dylib"
            else -> "so"
        }
        val prefix = if (os.contains("win")) "" else "lib"
        val archTag = if (arch.contains("aarch64") || arch.contains("arm")) "arm64" else "x64"

        from("${project.projectDir}/../target/release/${prefix}oxidecode_jvm.$ext")
        into("${project.projectDir}/src/main/resources/native")
        rename { "oxidecode_jvm_${archTag}.$ext" }

        outputs.upToDateWhen { false }
    }

    processResources {
        if (!project.hasProperty("skipNativeCopy")) {
            if (project.hasProperty("universal")) {
                dependsOn(copyNativeLibs)
            } else {
                dependsOn(copyNativeLib)
            }
        }
    }
}
