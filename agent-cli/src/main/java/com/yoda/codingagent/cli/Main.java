package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.CoreModule;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.printf(
                "Coding Agent development environment ready (%s, Java %d).%n",
                CoreModule.name(),
                Runtime.version().feature());
    }
}
