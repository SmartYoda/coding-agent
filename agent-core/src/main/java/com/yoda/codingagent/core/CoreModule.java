package com.yoda.codingagent.core;

/**
 * Temporary module marker used to verify the Java 21 build before core development starts.
 */
public final class CoreModule {

    private CoreModule() {
    }

    public static String name() {
        return "agent-core";
    }
}
