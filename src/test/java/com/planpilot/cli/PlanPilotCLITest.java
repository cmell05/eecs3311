package com.planpilot.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPilotCLITest {
    @Test
    void addsAndListsCourseThroughMenu() {
        String input = String.join("\n",
                "2", "C1", "EECS3311", "Software Design",
                "1", "0") + "\n";

        String output = runCli(input);

        assertTrue(output.contains("Course added."));
        assertTrue(output.contains("C1: EECS3311 - Software Design"));
    }

    @Test
    void unknownMenuChoiceDoesNotCrashApplication() {
        String output = assertDoesNotThrow(() -> runCli("unknown\n0\n"));

        assertTrue(output.contains("Unknown option"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void invalidInputReportsErrorAndReturnsToMenu() {
        String input = String.join("\n",
                "5", "A1", "not-a-date", "2026-10-28T10:00", "AVAILABLE",
                "0") + "\n";

        String output = assertDoesNotThrow(() -> runCli(input));

        assertTrue(output.contains("Error:"));
        assertTrue(output.contains("Goodbye."));
    }

    private String runCli(String input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PlanPilotCLI cli = new PlanPilotCLI(
                PlanPilotCLI.createDefaultController(),
                new Scanner(input), output);

        cli.run();

        return bytes.toString(StandardCharsets.UTF_8);
    }
}
