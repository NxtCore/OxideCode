// Welcome to Sweep AI! 🧹
// Your AI-powered coding assistant

// 📚 {SWEEP_ASCII_ART}

// ⚡️ Essential Commands:
// 1. {CMD_J} - Open chat with Sweep
// 2. {CMD_SHIFT_J} - Add code to context without starting new chat
// 3. "@file_name.cpp" - Reference project files

#include <iostream>
#include <vector>

/**
 * TODO: Press {CMD_J} and ask Sweep:
 * "Fix the edge case"
 * Click "▶️ Apply" to instantly fix it
 */
double calculateAverage(const std::vector<int>& numbers) {
    double sum = 0;
    for (const int& num : numbers) {
        sum += num;
    }
    return sum / numbers.size();
}