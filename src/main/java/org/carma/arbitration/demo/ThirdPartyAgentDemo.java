package org.carma.arbitration.demo;

import org.carma.arbitration.config.*;
import org.carma.arbitration.config.AgentConfigLoader.*;
import org.carma.arbitration.agent.RealisticAgentFramework.*;

import java.util.*;

/**
 * Demonstrates that third-party agents can be defined ENTIRELY in YAML
 * without any Java code changes to the platform.
 *
 * This proves the architectural goal:
 * "The platform supports introduction of arbitrary and previously unseen
 *  third-party (but structured and limited to conform with platform support) agents."
 *
 * Key points demonstrated:
 * 1. No Java class required for the agent
 * 2. Agent logic defined in Kotlin script within YAML
 * 3. Compile-time validation catches errors before runtime
 * 4. Security constraints enforced automatically
 */
public class ThirdPartyAgentDemo {

    public static void main(String[] args) {
        System.out.println("═".repeat(70));
        System.out.println("   THIRD-PARTY AGENT DEMONSTRATION");
        System.out.println("   Proving arbitrary agents work without Java changes");
        System.out.println("═".repeat(70));
        System.out.println();

        // ================================================================
        // EXAMPLE 1: Define a completely new agent type in YAML
        // ================================================================
        System.out.println("EXAMPLE 1: Custom Calculator Agent (defined inline)");
        System.out.println("-".repeat(50));

        String calculatorAgentYaml = """
            id: calculator-agent
            name: Math Calculator
            description: A custom agent that performs calculations
            type: scripted
            autonomy: TOOL
            currency: 50

            services:
              required: []

            domains:
              - math
              - computation

            initialization: |
              state["calculations"] = 0
              state["lastResult"] = 0.0

            execution: |
              val operation = goal.getParameter("operation") as? String ?: "add"
              val a = (goal.getParameter("a") as? Number)?.toDouble() ?: 0.0
              val b = (goal.getParameter("b") as? Number)?.toDouble() ?: 0.0

              val result = when (operation) {
                  "add" -> a + b
                  "subtract" -> a - b
                  "multiply" -> a * b
                  "divide" -> if (b != 0.0) a / b else Double.NaN
                  else -> Double.NaN
              }

              state["calculations"] = (state["calculations"] as Int) + 1
              state["lastResult"] = result

              publish("calculation_result", mapOf(
                  "operation" to operation,
                  "a" to a,
                  "b" to b,
                  "result" to result
              ))

              GoalResult.success(
                  "Calculated: $a $operation $b = $result",
                  mapOf("result" to result, "totalCalculations" to state["calculations"]),
                  System.currentTimeMillis() - startTime,
                  servicesUsed
              )

            goals:
              - id: calculate
                type: ONE_TIME
                description: Perform a calculation

            outputs:
              - type: memory
                name: calc-output
            """;

        try {
            AgentConfigLoader loader = new AgentConfigLoader();
            AgentConfig config = loader.loadFromString(calculatorAgentYaml);

            System.out.println("✓ YAML parsed successfully");
            System.out.println("  Agent ID: " + config.id);
            System.out.println("  Agent Name: " + config.name);
            System.out.println("  Type: " + config.type);
            System.out.println("  Has execution script: " + (config.execution != null));
            System.out.println("  Has initialization script: " + (config.initialization != null));
            System.out.println();

            // Validate the scripts
            KotlinScriptValidator validator = new KotlinScriptValidator();
            KotlinScriptValidator.ConfigValidationResult validation = validator.validateConfig(config);

            System.out.println("Validation Result: " + (validation.isValid() ? "VALID" : "INVALID"));
            if (!validation.getWarnings().isEmpty()) {
                System.out.println("  Warnings:");
                for (String w : validation.getWarnings()) {
                    System.out.println("    - " + w);
                }
            }
            System.out.println();

            // Build the agent
            Map<String, OutputChannel> channels = new HashMap<>();
            RealisticAgent agent = loader.buildRealisticAgent(config, channels);

            System.out.println("✓ Agent built successfully");
            System.out.println("  Agent class: " + agent.getClass().getSimpleName());
            System.out.println("  Autonomy level: " + agent.getAutonomyLevel());
            System.out.println("  Operating domains: " + agent.getOperatingDomains());
            System.out.println();

        } catch (Exception e) {
            System.out.println("✗ Failed: " + e.getMessage());
            e.printStackTrace();
        }

        // ================================================================
        // EXAMPLE 2: Security validation catches dangerous code
        // ================================================================
        System.out.println("EXAMPLE 2: Security Validation");
        System.out.println("-".repeat(50));

        String maliciousAgentYaml = """
            id: malicious-agent
            name: Attempted Malicious Agent
            type: scripted
            autonomy: TOOL

            execution: |
              // Attempt to execute shell commands (BLOCKED)
              val runtime = java.lang.Runtime.getRuntime()
              runtime.exec("rm -rf /")

              GoalResult.success("Done", mapOf(), 0, listOf())
            """;

        try {
            AgentConfigLoader loader = new AgentConfigLoader(false); // Disable auto-validation
            AgentConfig config = loader.loadFromString(maliciousAgentYaml);

            KotlinScriptValidator validator = new KotlinScriptValidator();
            KotlinScriptValidator.ConfigValidationResult validation = validator.validateConfig(config);

            System.out.println("Validation Result: " + (validation.isValid() ? "VALID" : "INVALID"));
            if (!validation.getErrors().isEmpty()) {
                System.out.println("  ✓ Security violations caught:");
                for (String e : validation.getErrors()) {
                    System.out.println("    - " + e);
                }
            }
            System.out.println();

        } catch (Exception e) {
            System.out.println("✗ Exception: " + e.getMessage());
        }

        // ================================================================
        // EXAMPLE 3: Syntax validation catches errors
        // ================================================================
        System.out.println("EXAMPLE 3: Syntax Validation");
        System.out.println("-".repeat(50));

        String syntaxErrorAgentYaml = """
            id: syntax-error-agent
            name: Agent with Syntax Error
            type: scripted
            autonomy: TOOL

            execution: |
              // Missing closing brace
              val result = mapOf(
                  "key" to "value"

              GoalResult.success("Done", result, 0, listOf())
            """;

        try {
            AgentConfigLoader loader = new AgentConfigLoader(false);
            AgentConfig config = loader.loadFromString(syntaxErrorAgentYaml);

            KotlinScriptValidator validator = new KotlinScriptValidator();
            KotlinScriptValidator.ConfigValidationResult validation = validator.validateConfig(config);

            System.out.println("Validation Result: " + (validation.isValid() ? "VALID" : "INVALID"));
            if (!validation.getErrors().isEmpty()) {
                System.out.println("  ✓ Syntax errors caught at load time:");
                for (String e : validation.getErrors()) {
                    // Truncate long error messages
                    String msg = e.length() > 100 ? e.substring(0, 100) + "..." : e;
                    System.out.println("    - " + msg);
                }
            }
            System.out.println();

        } catch (Exception e) {
            System.out.println("✗ Exception: " + e.getMessage());
        }

        // ================================================================
        // SUMMARY
        // ================================================================
        System.out.println("═".repeat(70));
        System.out.println("   SUMMARY: THIRD-PARTY AGENT SUPPORT");
        System.out.println("═".repeat(70));
        System.out.println();
        System.out.println("The platform now supports arbitrary third-party agents:");
        System.out.println();
        System.out.println("  1. NO JAVA CODE REQUIRED");
        System.out.println("     Agents defined entirely in YAML with Kotlin scripts");
        System.out.println();
        System.out.println("  2. COMPILE-TIME VALIDATION");
        System.out.println("     Scripts validated for syntax and security at load time");
        System.out.println();
        System.out.println("  3. SECURITY SANDBOXING");
        System.out.println("     Blocked: filesystem, network, reflection, process execution");
        System.out.println();
        System.out.println("  4. SYMMETRIC STRUCTURE");
        System.out.println("     initialization: runs once, execution: runs per goal");
        System.out.println();
        System.out.println("  5. PLATFORM INTEGRATION");
        System.out.println("     Scripts can invoke services, publish outputs, track state");
        System.out.println();
        System.out.println("This eliminates the need for Java classes per agent type,");
        System.out.println("enabling truly extensible multi-agent deployments.");
        System.out.println();
    }
}
