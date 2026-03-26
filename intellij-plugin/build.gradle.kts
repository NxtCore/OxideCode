plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.oxidecode"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3")
    type.set("IC")
    plugins.set(listOf())
}

kotlin {
    jvmToolchain(17)
}

tasks {
    buildSearchableOptions { enabled = false }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("243.*")
    }

    // Copy the compiled JVM native library into resources before building the jar.
    val copyNativeLib by registering(Copy::class) {
        val os = System.getProperty("os.name").lowercase()
        val ext = when {
            os.contains("win") -> "dll"
            os.contains("mac") -> "dylib"
            else -> "so"
        }
        val prefix = if (os.contains("win")) "" else "lib"
        from("${rootProject.projectDir}/../../target/release/${prefix}oxidecode_jvm.$ext")
        into("src/main/resources/native")
        rename { "oxidecode_jvm.$ext" }
    }

    processResources { dependsOn(copyNativeLib) }
}
