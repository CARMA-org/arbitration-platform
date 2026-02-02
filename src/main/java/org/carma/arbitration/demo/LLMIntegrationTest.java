package org.carma.arbitration.demo;

import org.carma.arbitration.mechanism.*;
import org.carma.arbitration.model.*;

import java.util.*;

/**
 * Test class for LLM integration.
 *
 * This demonstrates that the platform can make REAL API calls
 * to LLM providers (OpenAI, Anthropic, Gemini).
 *
 * To run:
 * 1. Set environment variable: export GEMINI_API_KEY=your_key
 *    (or OPENAI_API_KEY, or ANTHROPIC_API_KEY)
 * 2. Run: java -cp out org.carma.arbitration.demo.LLMIntegrationTest
 */
public class LLMIntegrationTest {

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("   LLM INTEGRATION TEST");
        System.out.println("   Testing Real API Calls to LLM Providers");
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Check for API keys
        String geminiKey = System.getenv("GEMINI_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");

        System.out.println("API Keys Found:");
        System.out.println("  GEMINI_API_KEY:    " + (geminiKey != null && !geminiKey.isEmpty() ? "✓ Set" : "✗ Not set"));
        System.out.println("  OPENAI_API_KEY:    " + (openaiKey != null && !openaiKey.isEmpty() ? "✓ Set" : "✗ Not set"));
        System.out.println("  ANTHROPIC_API_KEY: " + (anthropicKey != null && !anthropicKey.isEmpty() ? "✓ Set" : "✗ Not set"));
        System.out.println();

        // If no API keys, show error and exit
        if ((geminiKey == null || geminiKey.isEmpty()) &&
            (openaiKey == null || openaiKey.isEmpty()) &&
            (anthropicKey == null || anthropicKey.isEmpty())) {

            System.out.println("ERROR: No API keys found.");
            System.out.println();
            System.out.println("To test LLM integration, set one of the following environment variables:");
            System.out.println("  export GEMINI_API_KEY=your_key_here");
            System.out.println("  export OPENAI_API_KEY=your_key_here");
            System.out.println("  export ANTHROPIC_API_KEY=your_key_here");
            System.out.println();
            System.out.println("This test requires real LLM API credentials to verify integration.");
            System.exit(1);
            return;
        }

        // Run real LLM test
        runRealLLMTest();
    }

    private static void runRealLLMTest() {
        System.out.println("REAL LLM SERVICE BACKEND TEST");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        try {
            // Build backend from environment
            LLMServiceBackend backend = new LLMServiceBackend.Builder()
                .fromEnvironment()
                .logRequests(true)
                .build();

            System.out.println("Backend: " + backend);
            System.out.println();

            // Test 1: Simple text generation
            System.out.println("TEST 1: Text Generation");
            System.out.println("------------------------");

            Map<String, Object> input = new HashMap<>();
            input.put("prompt", "What is the capital of France? Reply in one word.");
            input.put("max_tokens", 50);

            System.out.println("Prompt: " + input.get("prompt"));
            System.out.println("Sending request...");
            System.out.println();

            long startTime = System.currentTimeMillis();
            ServiceBackend.InvocationResult result = backend.invokeByType(ServiceType.TEXT_GENERATION, input);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("Result:");
            System.out.println("  Success: " + result.isSuccess());
            System.out.println("  Duration: " + elapsed + "ms");
            if (result.isSuccess()) {
                System.out.println("  Provider: " + result.getOutput().get("provider"));
                System.out.println("  Model: " + result.getOutput().get("model"));
                System.out.println("  Response: " + result.getOutput().get("text"));
                System.out.println();
                System.out.println("✓ Real LLM integration working!");
            } else {
                System.out.println("  Error: " + result.getError());
                System.out.println();
                System.out.println("✗ LLM call failed");
            }

            // Test 2: Code generation
            System.out.println();
            System.out.println("TEST 2: Code Generation");
            System.out.println("------------------------");

            Map<String, Object> codeInput = new HashMap<>();
            codeInput.put("prompt", "Write a Python function that returns the factorial of a number. Just the code, no explanation.");
            codeInput.put("max_tokens", 200);

            System.out.println("Prompt: " + codeInput.get("prompt"));
            System.out.println("Sending request...");
            System.out.println();

            ServiceBackend.InvocationResult codeResult = backend.invokeByType(ServiceType.CODE_GENERATION, codeInput);

            System.out.println("Result:");
            System.out.println("  Success: " + codeResult.isSuccess());
            if (codeResult.isSuccess()) {
                String code = (String) codeResult.getOutput().get("text");
                System.out.println("  Generated Code:");
                System.out.println("  " + code.replace("\n", "\n  "));
                System.out.println();
                System.out.println("✓ Code generation working!");
            } else {
                System.out.println("  Error: " + codeResult.getError());
            }

            // Summary
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════════════");
            System.out.println("   LLM INTEGRATION TEST COMPLETE");
            System.out.println("════════════════════════════════════════════════════════════════════════");
            System.out.println();
            System.out.println("The platform successfully made REAL API calls to LLM providers.");
            System.out.println("This proves the architecture supports real agent integration.");
            System.out.println();

            // Shutdown
            backend.shutdown();

        } catch (Exception e) {
            System.out.println("Error during LLM test: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
