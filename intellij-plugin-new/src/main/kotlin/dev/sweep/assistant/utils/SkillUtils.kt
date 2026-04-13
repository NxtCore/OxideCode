package dev.sweep.assistant.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.Skill
import java.io.File

private val logger = Logger.getInstance("dev.sweep.assistant.utils.SkillUtils")

/**
 * Finds and parses all SKILL.md files from both personal and project skill directories.
 *
 * Personal skills: ~/.claude/skills/
 * Project skills: <project>/.claude/skills/
 *
 * Skills are formatted with YAML frontmatter containing name, description, and license,
 * followed by markdown content.
 *
 * Example skill file structure:
 * ---
 * name: skill-name
 * description: Skill description
 * license: License info
 * ---
 * # Skill Content
 * Markdown content here...
 */
fun findAndParseSkills(project: Project): List<Skill> {
    val skills = mutableListOf<Skill>()

    // 1. Find personal skills from ~/.claude/skills/
    val homeDir = System.getProperty("user.home")
    val personalSkillsDir = File(homeDir, ".claude/skills")
    if (personalSkillsDir.exists() && personalSkillsDir.isDirectory) {
        try {
            val personalSkillFiles = findSkillFilesInDirectory(personalSkillsDir)
            for (skillFile in personalSkillFiles) {
                try {
                    val skill = parseSkillFile(skillFile)
                    if (skill != null) {
                        skills.add(skill)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse personal skill file: ${skillFile.absolutePath}", e)
                }
            }
            logger.info("Found ${personalSkillFiles.size} personal skill(s)")
        } catch (e: Exception) {
            logger.warn("Error while searching for personal skill files", e)
        }
    }

    // 2. Find project skills from <project>/.claude/skills/
    val projectBasePath = project.basePath
    if (projectBasePath != null) {
        val projectSkillsDir = File(projectBasePath, ".claude/skills")
        if (projectSkillsDir.exists() && projectSkillsDir.isDirectory) {
            try {
                val projectSkillFiles = findSkillFilesInDirectory(projectSkillsDir)
                for (skillFile in projectSkillFiles) {
                    try {
                        val skill = parseSkillFile(skillFile)
                        if (skill != null) {
                            skills.add(skill)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse project skill file: ${skillFile.absolutePath}", e)
                    }
                }
                logger.info("Found ${projectSkillFiles.size} project skill(s)")
            } catch (e: Exception) {
                logger.warn("Error while searching for project skill files", e)
            }
        }
    }

    logger.info("Total skills found and parsed: ${skills.size}")
    return skills
}

/**
 * Finds all SKILL.md files in skill subdirectories.
 * Each skill should be in its own directory: <skills-dir>/<skill-name>/SKILL.md
 * Supports case-insensitive matching (skill.md, SKILL.md, Skill.md, etc.)
 */
private fun findSkillFilesInDirectory(skillsDirectory: File): List<File> {
    val skillFiles = mutableListOf<File>()

    try {
        // List all subdirectories in the skills directory
        val skillDirs = skillsDirectory.listFiles { file -> file.isDirectory } ?: emptyArray()

        for (skillDir in skillDirs) {
            // Look for SKILL.md file in each skill directory
            val skillFile =
                skillDir
                    .listFiles { file ->
                        file.isFile && file.name.equals("SKILL.md", ignoreCase = true)
                    }?.firstOrNull()

            if (skillFile != null) {
                skillFiles.add(skillFile)
            }
        }
    } catch (e: Exception) {
        logger.warn("Error while searching for skill files in: ${skillsDirectory.absolutePath}", e)
    }

    return skillFiles
}

/**
 * Parses a SKILL.md file and extracts the skill information.
 * Expected format:
 * ---
 * name: skill-name
 * description: Skill description
 * license: License info (optional)
 * ---
 * # Markdown content
 */
private fun parseSkillFile(file: File): Skill? {
    try {
        val content = file.readText()

        // Extract YAML frontmatter
        val frontMatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
        val match = frontMatterRegex.find(content)

        if (match == null) {
            logger.warn("No YAML frontmatter found in skill file: ${file.absolutePath}")
            return null
        }

        val frontMatter = match.groupValues[1]
        val skillContent = match.groupValues[2]

        // Parse frontmatter fields
        val nameMatch = Regex("""name:\s*(.+)""").find(frontMatter)
        val descriptionMatch = Regex("""description:\s*(.+)""").find(frontMatter)

        val name = nameMatch?.groupValues?.get(1)?.trim()
        val description = descriptionMatch?.groupValues?.get(1)?.trim()

        if (name.isNullOrBlank() || description.isNullOrBlank()) {
            logger.warn("Missing required fields (name or description) in skill file: ${file.absolutePath}")
            return null
        }

        // Get absolute path from file
        val fullPath = file.absolutePath

        return Skill(
            name = name,
            description = description,
            frontMatter = frontMatter,
            content = skillContent.trim(),
            absolutePath = fullPath,
        )
    } catch (e: Exception) {
        logger.warn("Failed to parse skill file: ${file.absolutePath}", e)
        return null
    }
}
