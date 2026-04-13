import com.google.protobuf.gradle.id
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    application
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.0"
    kotlin("jvm") version "2.1.0"
    id("com.google.protobuf") version "0.9.4"
    kotlin("plugin.serialization") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val remoteRobotVersion = "0.11.20"
val pluginId = "dev.sweep.assistant"
val pluginName = "Self-Hosted Enterprise Updater"
println("Building plugin: $pluginName with ID: $pluginId")
group = "dev.sweep"
version = "1.29.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    autoReload.set(false) // this triggers unloading which is very annoying
    buildSearchableOptions.set(false)
    pluginConfiguration {
        id.set(pluginId)
        name.set(pluginName)
    }

    pluginVerification {
        ides {
            select {
                types.set(listOf(IntelliJPlatformType.WebStorm))
                channels.set(listOf(ProductRelease.Channel.RELEASE))
                sinceBuild.set("242")
                untilBuild.set("243.*")
            }
//            select {
//                types.set(listOf(IntelliJPlatformType.DataGrip))
//                channels.set(listOf(ProductRelease.Channel.RELEASE))
//                sinceBuild.set("241") // 23x doesn't support vcs
//                untilBuild.set("243.*")
//            }
            select {
                types.set(
                    listOf(
                        IntelliJPlatformType.IntellijIdeaUltimate,
                        IntelliJPlatformType.IntellijIdeaCommunity,
                        IntelliJPlatformType.GoLand,
                        IntelliJPlatformType.PyCharmCommunity,
                        IntelliJPlatformType.PyCharmProfessional,
                        IntelliJPlatformType.CLion,
                        IntelliJPlatformType.Rider,
                        IntelliJPlatformType.AndroidStudio,
                        IntelliJPlatformType.RustRover,
                        IntelliJPlatformType.RubyMine,
                        IntelliJPlatformType.PhpStorm,
                    ),
                )
                channels.set(listOf(ProductRelease.Channel.RELEASE))
                sinceBuild.set("241")
                untilBuild.set("261.*")
            }
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

    val copyNativeLibs by creating(Copy::class) {
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

    val copyNativeLib by creating(Copy::class) {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val ext =
            when {
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

    // Need JDK 17 for Intellij 2024.1.7

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("261.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    processResources {
        // Set duplicate strategy for all files
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        if (!project.hasProperty("skipNativeCopy")) {
            if (project.hasProperty("universal")) {
                dependsOn(copyNativeLibs)
            } else {
                dependsOn(copyNativeLib)
            }
        }

        // Ensure ripgrep binaries are included
        from("src/main/resources") {
            include("tools/**")
        }
    }

    processTestResources {
        // Set duplicate strategy for test resources
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Fix: Create a Copy task instead of DefaultTask for configuration cache compatibility
    val copyRipgrepToSandbox by creating(Copy::class) {
        val sandboxPluginDir =
            layout.buildDirectory
                .dir("idea-sandbox/plugins/${project.name}")

        from("src/main/resources/tools") {
            include("ripgrep/macos-aarch64/rg")
        }
        into(sandboxPluginDir.map { it.dir("tools") })

        doLast {
            println("Copied ripgrep to sandbox")
        }
    }

    // Hook the copy task to prepareSandbox
    prepareSandbox {
        finalizedBy(copyRipgrepToSandbox)
    }

    buildPlugin {
        archiveBaseName.set("sweepai")

        // Ensure the ripgrep binary is included in the plugin JAR
        from("src/main/resources") {
            include("tools/ripgrep/**")
            into("lib/tools")
        }
    }

    runIde {
        jvmArgs("-Xmx2g") // Set maximum heap size
        jvmArgs("-XX:ReservedCodeCacheSize=512m") // Set code cache size
        jvmArgs("-XX:+UseG1GC") // Use G1 garbage collector
        jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50") // Soft references policy
        jvmArgs("-ea") // Enable assertions
        jvmArgs("-Djava.net.preferIPv4Stack=true") // Prefer IPv4
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
        jvmArgs(
            "-XX:StartFlightRecording=filename=${project.layout.buildDirectory.get().asFile}/pluginPerf.jfr,settings=profile,duration=60s,maxsize=1g,dumponexit=true",
        )
    }

    task<Exec>("e2e") {
        commandLine("./bin/e2e")
    }

    task<Exec>("format") {
        group = "format"
        commandLine("./bin/format")
    }

    task<Exec>("release") {
        group = "plugin"
        commandLine("./bin/release")
    }

    task<Exec>("installPlugin") {
        group = "plugin"
        commandLine("./bin/install")
    }

    task<Exec>("installCloudPlugin") {
        group = "plugin"
        commandLine("./bin/install", "--cloud")
    }

    task<Exec>("uninstallPlugin") {
        group = "plugin"
        commandLine("./bin/uninstall")
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("idea.use.core.classloader.for.plugin.path", "true")
    }

    verifyPlugin {}
}

val mcpVersion = "0.8.0"

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.21.0")
    implementation("com.flipkart.zjsonpatch:zjsonpatch:0.4.14") {
        // Exclude Jackson dependencies since we'll use IntelliJ's
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "com.fasterxml.jackson.databind")
    }
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.23.4")
    // MCP SDK - use JVM-specific artifacts for better compatibility
    implementation("io.modelcontextprotocol:kotlin-sdk-client-jvm:$mcpVersion") {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-jdk8")
    }
    implementation("io.modelcontextprotocol:kotlin-sdk-core-jvm:$mcpVersion") {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-jdk8")
    }
    // Ktor client with CIO engine for MCP HTTP transports
    implementation("io.ktor:ktor-client-cio:3.2.3") {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-jdk8")
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-slf4j")
    }
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.aayushatharva.brotli4j:brotli4j:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-osx-aarch64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-osx-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-linux-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-linux-aarch64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-windows-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-windows-aarch64:1.16.0")

    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("com.automation-remarks:video-recorder-junit5:2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("io.kotest:kotest-assertions-core:5.6.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")

    testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
    testImplementation("com.squareup.okhttp3:okhttp:4.11.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    intellijPlatform {
        // https://www.jetbrains.com/idea/download/other.html
//        androidStudio("2024.3.2.11") // Android Studio Meerkat | 2024.3.2 Patch 11
//        androidStudio("2025.1.1.11") // Android Studio Narwhal | 2025.1.1 Patch 11
        intellijIdeaCommunity("2025.1")
//        rustRover("2025.1")
//        intellijIdeaCommunity("2023.3.8")
//        intellijIdeaCommunity("2023.1.7")
//        intellijIdeaCommunity("2023.1.2")
//        intellijIdeaUltimate("2025.1")
//        pycharmCommunity("2024.2.4")
//        pycharmCommunity("2024.3.4")
//        pycharmCommunity("2025.1.1")
//        pycharmProfessional("2025.1")
        bundledPlugins("org.jetbrains.plugins.terminal", "org.intellij.plugins.markdown", "Git4Idea")
//        plugin("IdeaVIM:2.19.0")
//        plugin("org.jetbrains.completion.full.line:241.18034.76") // requires  intellijIdeaUltimate("2024.1.7")
//        plugin("com.github.copilot:1.5.45-243") // requires kotlin 2.1.0 and a lot of memory
        testFramework(TestFrameworkType.Platform)
    }
}

configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs(
                "src/main/kotlin",
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin",
                "scripts",
            )
            // Add resources directory explicitly
            resources.srcDirs("src/main/resources")
        }
        test {
            kotlin.srcDirs("src/test/kotlin")
            resources.srcDirs("src/test/resources")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.23.4"
    }

    // Generate Kotlin code
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                args("${project.projectDir}")
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=8082",
                            "-Didea.trust.all.projects=true",
                            "-Dide.mac.message.dialogs.as.sheets=false",
                            "-Djb.privacy.policy.text=<!--999.999-->",
                            "-Djb.consents.confirmation.enabled=false",
                            "-Dide.show.tips.on.startup.default.value=false",
                        )
                    }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// Task to run the llama.cpp test
tasks.register<Test>("runLlamaCppTest") {
    group = "verification"
    description = "Run the llama.cpp integration test"

    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.sweep.assistant.autocomplete.llamacpp.LlamaCppTest")
    }

    // Enable the test
    systemProperty("llama.test.enabled", "true")

    // Optional: override model path
    // systemProperty("llama.model.path", "/path/to/your/model.gguf")

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}
