package org.carma.arbitration.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompositionValidationTest {

    @Test
    void typeIncompatibleCompositionRejectedAtRegistration() {
        // Text-to-speech outputs AUDIO; OCR only accepts IMAGE.
        ServiceComposition invalid = new ServiceComposition.Builder("bad-types")
            .addNode("a", ServiceType.TEXT_TO_SPEECH)
            .addNode("b", ServiceType.OCR)
            .connect("a", "b", ServiceType.DataType.AUDIO)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerComposition(invalid));
    }

    @Test
    void cyclicCompositionRejectedAtRegistration() {
        // A -> B -> A is type-compatible on every edge but forms a cycle.
        ServiceComposition cyclic = new ServiceComposition.Builder("cyclic")
            .addNode("a", ServiceType.TEXT_GENERATION)
            .addNode("b", ServiceType.TEXT_SUMMARIZATION)
            .connect("a", "b", ServiceType.DataType.TEXT)
            .connect("b", "a", ServiceType.DataType.TEXT)
            .build();
        assertTrue(cyclic.validate().getErrors().stream()
                .anyMatch(e -> e.toLowerCase().contains("cycle")),
            "cycle must be reported");
        ServiceRegistry registry = new ServiceRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerComposition(cyclic));
    }

    @Test
    void validLinearCompositionRegisters() {
        ServiceComposition valid = ServiceComposition.linearChain(
            "ok", ServiceType.TEXT_GENERATION, ServiceType.TEXT_SUMMARIZATION);
        ServiceRegistry registry = new ServiceRegistry();
        assertDoesNotThrow(() -> registry.registerComposition(valid));
        assertTrue(registry.getComposition("ok").isPresent());
    }
}
