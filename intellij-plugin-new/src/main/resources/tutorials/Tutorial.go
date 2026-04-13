// Welcome to Sweep AI! 🧹
// Your AI-powered coding assistant

// 📚 {SWEEP_ASCII_ART}

// ⚡️ Essential Commands:
// 1. {CMD_J} - Open chat with Sweep
// 2. {CMD_SHIFT_J} - Add code to context without starting new chat
// 3. "@file_name.go" - Reference project files

package main

import (
	"fmt"
)

/**
 * TODO: Press {CMD_J} and ask Sweep:
 * "Fix the edge case"
 * Click "▶️ Apply" to instantly fix it
 */
func calculateAverage(numbers []int) float64 {
	sum := 0.0
	for _, num := range numbers {
		sum += float64(num)
	}
	return sum / float64(len(numbers))
}
