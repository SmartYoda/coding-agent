package com.yoda.codingagent.cli;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.printf("Coding Agent CLI (Java %d).%n", Runtime.version().feature());
        System.out.println(
                "Day 1 core foundation is ready; interactive CLI wiring is scheduled for Day 3.");
    }
}
