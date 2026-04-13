// Welcome to Sweep AI! 🧹
// Your AI-powered coding assistant

// 📚 {SWEEP_ASCII_ART}

// ⚡️ Essential Commands:
// 1. {CMD_J} - Open chat with Sweep
// 2. {CMD_SHIFT_J} - Add code to context without starting new chat
// 3. "@file_name.kt" - Reference project files

/**
 * TODO: Press {CMD_J} and ask Sweep: "Fix the edge case"
 * Click "▶️ Apply" to instantly fix it
 */
fun calculateAverage(numbers: List<Int>): Double {
    var sum = 0.0
    for (num in numbers) {
        sum += num
    }
    return sum / numbers.size
}
