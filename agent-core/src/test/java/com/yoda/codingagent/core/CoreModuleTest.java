package com.yoda.codingagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreModuleTest {

    @Test
    void buildUsesJava21OrNewer() {
        assertTrue(Runtime.version().feature() >= 21);
        assertEquals("agent-core", CoreModule.name());
    }
}
