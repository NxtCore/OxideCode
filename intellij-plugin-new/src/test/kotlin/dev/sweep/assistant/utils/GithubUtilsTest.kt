package dev.sweep.assistant.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GithubUtilsTest {
    @Test
    fun `test parseGitUrl with SSH URL`() {
        val sshUrl = "git@github.com:sweepai/jetbrains.git"
        parseGitUrl(sshUrl) shouldBe "sweepai/jetbrains"
    }

    @Test
    fun `test parseGitUrl with HTTPS URL`() {
        val httpsUrl = "https://github.com/sweepai/jetbrains.git"
        parseGitUrl(httpsUrl) shouldBe "sweepai/jetbrains"
    }

    @Test
    fun `test parseGitUrl with HTTPS Enterprise URL`() {
        val httpsUrl = "https://github.company.com/sweepai/jetbrains.git"
        parseGitUrl(httpsUrl) shouldBe "sweepai/jetbrains"
    }

    @Test
    fun `test parseGitUrl with null URL`() {
        parseGitUrl(null) shouldBe ""
    }

    @Test
    fun `test parseGitUrl with malformed URL`() {
        parseGitUrl("not-a-git-url") shouldBe ""
    }

    @Test
    fun `test parseGitUrl with empty URL`() {
        parseGitUrl("") shouldBe ""
    }
}
