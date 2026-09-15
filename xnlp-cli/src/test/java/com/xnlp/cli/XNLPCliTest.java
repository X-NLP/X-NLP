package com.xnlp.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class XNLPCliTest {

    @Test
    void rootHelp_listsProviderAndBenchmarkCommands() {
        CommandResult result = execute("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("provider", "benchmark");
    }

    @Test
    void providerHelp_listsStatusAndProbeCommands() {
        CommandResult result = execute("provider", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("status", "probe");
    }

    @Test
    void benchmarkHelp_documentsEngineeringLimits() {
        CommandResult result = execute("benchmark", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("--model")
                .contains("--requests")
                .contains("1-10000")
                .contains("--concurrency")
                .contains("1-256");
    }


    @Test
    void benchmarkValidation_returnsStableUsageErrorWithoutStackTrace() {
        CommandResult result = execute("benchmark", "--model", "test", "--requests", "0");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("xnlp: requests must be between 1 and 10000")
                .doesNotContain("Exception", "at com.xnlp");
    }

    private CommandResult execute(String... args) {
        StringWriter output = new StringWriter();
        CommandLine commandLine = XNLPCli.createCommandLine();
        commandLine.setOut(new PrintWriter(output));
        commandLine.setErr(new PrintWriter(output));
        return new CommandResult(commandLine.execute(args), output.toString());
    }

    private record CommandResult(int exitCode, String output) {
    }
}
