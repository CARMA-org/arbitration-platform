package org.carma.arbitration.demo;

import org.carma.arbitration.runner.ScenarioRunner;
import org.carma.arbitration.runner.ScenarioRunner.ScenarioResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Demonstrates the config-driven arbitration system.
 *
 * This demo:
 * 1. Lists available scenarios from config/scenarios/
 * 2. Runs each scenario using the ScenarioRunner
 * 3. Uses ContentionDetector for automatic contention detection
 * 4. Reports results clearly
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass=org.carma.arbitration.demo.ConfigDrivenDemo
 *
 * Or with a specific scenario:
 *   mvn exec:java -Dexec.mainClass=org.carma.arbitration.demo.ConfigDrivenDemo -Dexec.args=basic-arbitration
 */
public class ConfigDrivenDemo {

    private static final String SEP = "=".repeat(70);
    private static final String SUBSEP = "-".repeat(50);

    public static void main(String[] args) {
        System.out.println(SEP);
        System.out.println("CARMA CONFIG-DRIVEN ARBITRATION DEMONSTRATION");
        System.out.println(SEP);
        System.out.println();
        System.out.println("This demo showcases the config-driven approach to agent arbitration:");
        System.out.println("  - Agents defined in YAML configuration files");
        System.out.println("  - Scenarios organized in subdirectories");
        System.out.println("  - Automatic contention detection via ContentionDetector");
        System.out.println("  - No manual Java construction required");
        System.out.println();

        Path configRoot = findConfigRoot();
        if (configRoot == null) {
            System.err.println("ERROR: Could not find config directory.");
            System.err.println("Expected: ./config/scenarios/ or similar");
            System.exit(1);
        }

        ScenarioRunner runner = new ScenarioRunner();

        try {
            if (args.length > 0) {
                // Run specific scenario
                String scenarioName = args[0];
                runScenario(runner, configRoot, scenarioName);
            } else {
                // List and run all scenarios
                runAllScenarios(runner, configRoot);
            }
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println();
        System.out.println(SEP);
        System.out.println("CONFIG-DRIVEN DEMO COMPLETE");
        System.out.println(SEP);
    }

    /**
     * Run all available scenarios.
     */
    private static void runAllScenarios(ScenarioRunner runner, Path configRoot) throws IOException {
        List<String> scenarios = runner.listScenarios(configRoot);

        if (scenarios.isEmpty()) {
            System.out.println("No scenarios found in: " + configRoot.resolve("scenarios"));
            System.out.println();
            System.out.println("To create a scenario:");
            System.out.println("  1. Create a directory: config/scenarios/<name>/");
            System.out.println("  2. Add scenario.yaml with pool and arbitration settings");
            System.out.println("  3. Add agent YAML files (one per agent)");
            return;
        }

        System.out.println("Available Scenarios:");
        for (String scenario : scenarios) {
            System.out.println("  - " + scenario);
        }
        System.out.println();

        // Run each scenario
        for (int i = 0; i < scenarios.size(); i++) {
            String scenario = scenarios.get(i);
            System.out.println();
            System.out.println(SEP);
            System.out.println("SCENARIO " + (i + 1) + "/" + scenarios.size() + ": " + scenario.toUpperCase());
            System.out.println(SEP);
            System.out.println();

            runScenario(runner, configRoot, scenario);

            if (i < scenarios.size() - 1) {
                System.out.println();
                System.out.println(SUBSEP);
            }
        }
    }

    /**
     * Run a single scenario.
     */
    private static void runScenario(ScenarioRunner runner, Path configRoot, String scenarioName) throws IOException {
        Path scenarioDir = configRoot.resolve("scenarios").resolve(scenarioName);

        if (!Files.exists(scenarioDir)) {
            System.err.println("Scenario not found: " + scenarioDir);
            return;
        }

        if (!Files.exists(scenarioDir.resolve("scenario.yaml"))) {
            System.err.println("Missing scenario.yaml in: " + scenarioDir);
            return;
        }

        System.out.println("Running scenario from: " + scenarioDir);
        System.out.println();

        try {
            ScenarioResult result = runner.run(scenarioDir);

            // Additional summary
            System.out.println();
            System.out.println("Scenario completed successfully.");
            System.out.println(result);

        } catch (Exception e) {
            System.err.println("ERROR running scenario: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find the config root directory.
     */
    private static Path findConfigRoot() {
        // Try current directory
        Path current = Paths.get("config");
        if (Files.exists(current) && Files.exists(current.resolve("scenarios"))) {
            return current;
        }

        // Try relative to working directory
        current = Paths.get("./config");
        if (Files.exists(current) && Files.exists(current.resolve("scenarios"))) {
            return current;
        }

        // Try parent directory (for when running from target/)
        current = Paths.get("../config");
        if (Files.exists(current) && Files.exists(current.resolve("scenarios"))) {
            return current;
        }

        // Try absolute path from system property
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            current = Paths.get(userDir, "config");
            if (Files.exists(current) && Files.exists(current.resolve("scenarios"))) {
                return current;
            }
        }

        return null;
    }

    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  ConfigDrivenDemo              - Run all scenarios");
        System.out.println("  ConfigDrivenDemo <scenario>   - Run specific scenario");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ConfigDrivenDemo basic-arbitration");
        System.out.println("  ConfigDrivenDemo service-competition");
        System.out.println("  ConfigDrivenDemo multi-resource");
        System.out.println("  ConfigDrivenDemo safety-monitoring");
    }
}
