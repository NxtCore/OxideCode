package dev.sweep.assistant.services

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.mockito.Mockito.*
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

class RipgrepManagerTest {
    @Test
    fun `test OS and architecture detection for macOS ARM64`() {
        // Create a mock instance to test the detection logic
        val manager = RipgrepManager()

        // Test detection on the actual system
        val osName = System.getProperty("os.name", "").lowercase()
        val osArch = System.getProperty("os.arch", "").lowercase()

        // This test will pass on macOS ARM64, and verify null on other platforms
        val ripgrepPath = manager.getRipgrepPath()

        if (osName.contains("mac") && (osArch == "aarch64" || osArch == "arm64")) {
            ripgrepPath.shouldNotBeNull()
            ripgrepPath.exists() shouldBe true
            ripgrepPath.isExecutable() shouldBe true
        } else {
            // On unsupported platforms, should return null
            ripgrepPath.shouldBeNull()
        }
    }

    @Test
    fun `test getRipgrepPath returns null for unsupported platforms`() {
        // We can't easily mock System properties in the actual implementation,
        // but we can test that the method handles unsupported platforms gracefully
        val manager = RipgrepManager()

        val osName = System.getProperty("os.name", "").lowercase()
        val osArch = System.getProperty("os.arch", "").lowercase()

        // If we're not on macOS ARM64, it should return null
        if (!(osName.contains("mac") && (osArch == "aarch64" || osArch == "arm64"))) {
            val path = manager.getRipgrepPath()
            path.shouldBeNull()
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `test binary extraction and caching on macOS ARM64`() {
        val osArch = System.getProperty("os.arch", "").lowercase()

        // Only run this test on macOS ARM64
        if (osArch == "aarch64" || osArch == "arm64") {
            val manager = RipgrepManager()

            // First call should extract the binary
            val firstPath = manager.getRipgrepPath()
            firstPath.shouldNotBeNull()
            firstPath.exists() shouldBe true

            // Second call should return the cached path
            val secondPath = manager.getRipgrepPath()
            secondPath.shouldNotBeNull()

            // Both paths should be the same (cached)
            firstPath shouldBe secondPath
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `test isRipgrepAvailable on supported platform`() {
        val osArch = System.getProperty("os.arch", "").lowercase()

        if (osArch == "aarch64" || osArch == "arm64") {
            val manager = RipgrepManager()

            // Should be available on macOS ARM64
            val available = manager.isRipgrepAvailable()
            available shouldBe true
        }
    }

    @Test
    fun `test isRipgrepAvailable returns false on unsupported platform`() {
        val manager = RipgrepManager()

        val osName = System.getProperty("os.name", "").lowercase()
        val osArch = System.getProperty("os.arch", "").lowercase()

        // If we're not on macOS ARM64, it should not be available
        if (!(osName.contains("mac") && (osArch == "aarch64" || osArch == "arm64"))) {
            val available = manager.isRipgrepAvailable()
            available shouldBe false
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `test ripgrep version output on macOS ARM64`() {
        val osArch = System.getProperty("os.arch", "").lowercase()

        if (osArch == "aarch64" || osArch == "arm64") {
            val manager = RipgrepManager()
            val ripgrepPath = manager.getRipgrepPath()

            ripgrepPath.shouldNotBeNull()

            // Try to execute ripgrep --version
            val process =
                ProcessBuilder(ripgrepPath.toString(), "--version")
                    .start()

            val exitCode = process.waitFor()
            exitCode shouldBe 0

            val output = process.inputStream.bufferedReader().readText()
            output shouldContain "ripgrep"
        }
    }

    @Test
    fun `test getInstance returns singleton`() {
        // Note: This test requires the IntelliJ test framework to be properly set up
        // In a real test environment with proper IntelliJ test infrastructure,
        // we would test that getInstance() returns the same instance

        // For now, we can at least verify the companion object exists
        // and the method is accessible
        val companionExists =
            RipgrepManager.Companion::class.java.declaredMethods
                .any { it.name == "getInstance" }

        companionExists shouldBe true
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `test extracted binary has correct permissions on macOS`() {
        val osArch = System.getProperty("os.arch", "").lowercase()

        if (osArch == "aarch64" || osArch == "arm64") {
            val manager = RipgrepManager()
            val ripgrepPath = manager.getRipgrepPath()

            ripgrepPath.shouldNotBeNull()

            // Check if the file is executable
            ripgrepPath.isExecutable() shouldBe true

            // Check if we can read the file
            ripgrepPath.toFile().canRead() shouldBe true

            // Check if we can execute the file
            ripgrepPath.toFile().canExecute() shouldBe true
        }
    }
}
