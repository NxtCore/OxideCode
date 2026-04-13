import java.io.File

fun findLinkedWord(
    keyword: String,
    basePath: String?,
    relativePath: String,
): Pair<String, Int>? {
    try {
        val file = File(basePath, relativePath)
        if (file.exists() && file.isFile) {
            val content = file.readText()

            // Determine language based on file extension
            val isKotlinFile = relativePath.endsWith(".kt") || relativePath.endsWith(".kts")
            val isJavaFile = relativePath.endsWith(".java")
            val isPythonFile = relativePath.endsWith(".py")
            val isTypeScriptFile = relativePath.endsWith(".ts") || relativePath.endsWith(".tsx")
            val isCppFile =
                relativePath.endsWith(".cpp") ||
                    relativePath.endsWith(".hpp") ||
                    relativePath.endsWith(".cc") ||
                    relativePath.endsWith(".h")

            // Select appropriate patterns based on language
            val patterns =
                when {
                    isKotlinFile ->
                        listOf(
                            "fun\\s+${Regex.escape(keyword)}\\b", // Functions (with or without parameters)
                            "(class|interface|object|enum\\s+class|typealias)\\s+${Regex.escape(keyword)}\\b", // Class-like declarations
                        )
                    isJavaFile ->
                        listOf(
                            "\\b(public|private|protected|static|final|\\s)*\\s+\\w+\\s+${Regex.escape(keyword)}\\b\\s*\\(", // Methods
                            "\\b(public|private|protected|static|final|\\s)*\\s+(class|interface|enum|@interface)\\s+${Regex.escape(
                                keyword,
                            )}\\b", // Type declarations
                        )
                    isPythonFile ->
                        listOf(
                            "(async\\s+)?def\\s+${Regex.escape(keyword)}\\b(?=\\s*\\()", // Functions (including async)
                            "class\\s+${Regex.escape(keyword)}\\b(?=\\s*[:\\(])", // Classes
                        )
                    isTypeScriptFile ->
                        listOf(
                            "(async\\s+)?function\\s+${Regex.escape(keyword)}\\b", // Functions (including async)
                            "(export\\s+)?(default\\s+)?(class|interface|enum|type)\\s+${Regex.escape(keyword)}\\b", // Type declarations
                            "(export\\s+)?namespace\\s+${Regex.escape(keyword)}\\b", // Namespaces
                        )
                    isCppFile ->
                        listOf(
                            "\\b\\w+\\s+${Regex.escape(keyword)}\\b\\s*\\(", // Functions
                            "(class|struct|enum(\\s+class)?|namespace)\\s+${Regex.escape(keyword)}\\b", // Type declarations
                            "(typedef|template\\s*<.*>\\s*(class|struct))\\s+${Regex.escape(keyword)}\\b", // Typedefs and templates
                            "#define\\s+${Regex.escape(keyword)}\\b", // Macros
                        )
                    else -> listOf("\\b${Regex.escape(keyword)}\\b")
                }

            for (pattern in patterns) {
                val regex = Regex(pattern)
                val lineNumber =
                    content.lines().indexOfFirst { line ->
                        regex.containsMatchIn(line)
                    }
                if (lineNumber >= 0) {
                    return Pair(relativePath, lineNumber + 1)
                }
            }
        }
        return null
    } catch (e: Exception) {
        return null
    }
}

fun findKeywordDirectlyInFile(
    keyword: String,
    basePath: String?,
    relativePath: String,
): Pair<String, Int>? {
    try {
        val file = File(basePath, relativePath)
        if (file.exists() && file.isFile) {
            val content = file.readText()
            val lines = content.lines()

            // Use word boundary regex to match whole words only
            // This prevents "debu" from matching "debug_info"
            val wordBoundaryRegex = Regex("\\b${Regex.escape(keyword)}\\b")

            // Find the first line containing the keyword as a whole word
            val lineNumber =
                lines.indexOfFirst { line ->
                    wordBoundaryRegex.containsMatchIn(line)
                }

            if (lineNumber >= 0) {
                return Pair(relativePath, lineNumber + 1)
            }
        }
        return null
    } catch (e: Exception) {
        return null
    }
}
