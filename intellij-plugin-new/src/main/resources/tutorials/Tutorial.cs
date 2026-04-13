// Welcome to Sweep AI! 🧹
// Your AI-powered coding assistant

// 📚 {SWEEP_ASCII_ART}


using System;
using System.Collections.Generic;

// ⚡️ Essentials:
// 1. {CMD_J} - Open chat with Sweep
// 2. {CMD_SHIFT_J} - Add code to context without starting new chat
// 3. "@file_name.cs" - Reference project files

/**
 * TODO: Press {CMD_J} and ask Sweep: "Fix the edge case"
 * Click "▶️ Apply" to instantly fix it
 */
public static double CalculateAverage(int[] numbers)
{
    double sum = 0;
    foreach (int num in numbers)
    {
        sum += num;
    }
    return sum / numbers.Length;
}