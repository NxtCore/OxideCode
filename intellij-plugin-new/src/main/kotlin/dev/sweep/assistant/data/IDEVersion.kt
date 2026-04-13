package dev.sweep.assistant.data

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.util.BuildNumber
import com.intellij.util.text.SemVer

/**
 * Represents an IDE version that can be compared with other versions.
 * Supports semantic versioning comparison.
 */
class IDEVersion private constructor(
    private val semVer: SemVer,
) : Comparable<IDEVersion> {
    companion object {
        private val semverRegex = Regex("""\d+(?:\.\d+){1,2}""") // grabs "2024.3" or "2024.3.6" from strings like "2024.3 EAP"

        private fun normalizeToSemVerString(version: String): String {
            // Keep only numeric parts
            val parts = version.split(Regex("""\D+""")).filter { it.isNotEmpty() }
            return when (parts.size) {
                1 -> "${parts[0]}.0.0"
                2 -> "${parts[0]}.${parts[1]}.0"
                else -> "${parts[0]}.${parts[1]}.${parts[2]}"
            }
        }

        /** Returns the current IDE's version */
        fun current(): IDEVersion {
            val info = ApplicationInfo.getInstance()
            val raw = info.fullVersion // "2024.3 EAP" / "2024.3.1.0"
            val normalized = normalizeToSemVerString(raw)
            val semVer = SemVer.parseFromText(normalized) ?: SemVer.parseFromText("0.0.0")!!
            return IDEVersion(semVer)
        }

        /** Creates an IDEVersion from a version string (e.g., "2024.3.6" or "2024.3") */
        fun fromString(version: String): IDEVersion {
            val cleaned = semverRegex.find(version)?.value ?: version
            val semVer = SemVer.parseFromText(cleaned) ?: SemVer.parseFromText("0.0.0")!!
            return IDEVersion(semVer)
        }

        /** Creates an IDEVersion from major.minor.patch components */
        fun fromComponents(
            major: Int,
            minor: Int,
            patch: Int = 0,
        ): IDEVersion {
            val versionString = "$major.$minor.$patch"
            val semVer = SemVer.parseFromText(versionString) ?: SemVer.parseFromText("0.0.0")!!
            return IDEVersion(semVer)
        }

        /** Extra helpers for current IDE version */
        fun isEap(): Boolean = ApplicationInfoEx.getInstanceEx().isEAP

        fun buildNumber(): BuildNumber = ApplicationInfo.getInstance().build // e.g., 243.x.y (== 2024.3)

        fun baseline(): Int = buildNumber().baselineVersion // e.g., 243
    }

    /** Returns the underlying SemVer object */
    fun semVer(): SemVer = semVer

    /** true if this version >= target version */
    fun isAtLeast(other: IDEVersion): Boolean = this >= other

    /** true if this version >= target (e.g., isAtLeast(2024,3,6)) */
    fun isAtLeast(
        major: Int,
        minor: Int,
        patch: Int = 0,
    ): Boolean = this >= fromComponents(major, minor, patch)

    /** String variant: isAtLeast("2024.3.6") */
    fun isAtLeast(version: String): Boolean = this >= fromString(version)

    /** Returns true if this version is newer than the other */
    fun isNewerThan(other: IDEVersion): Boolean = this > other

    /** Returns true if this version is older than the other */
    fun isOlderThan(other: IDEVersion): Boolean = this < other

    override fun compareTo(other: IDEVersion): Int = semVer.compareTo(other.semVer)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IDEVersion) return false
        return semVer == other.semVer
    }

    override fun hashCode(): Int = semVer.hashCode()

    override fun toString(): String = semVer.toString()
}
