// Welcome to Sweep AI! 🧹
// Your AI-powered coding assistant

// 📚 {SWEEP_ASCII_ART}

// ⚡️ Essentials:
// 1. {CMD_J} - Open chat with Sweep
// 2. {CMD_SHIFT_J} - Add code to context without starting new chat
// 3. "@file_name.rs" - Reference project files

/**
 * TODO: Press {CMD_J} and ask Sweep:
 * "Fix the edge case"
 * Click "▶️ Apply" to instantly fix it
 */
fn calculate_average(numbers: &Vec<i32>) -> f64 {
    let mut sum = 0.0;
    for num in numbers {
        sum += *num as f64;
    }
    sum / numbers.len() as f64
}
