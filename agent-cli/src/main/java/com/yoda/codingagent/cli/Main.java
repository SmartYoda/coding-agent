package com.yoda.codingagent.cli;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CliApplication().run(
                args, System.getenv(), System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
