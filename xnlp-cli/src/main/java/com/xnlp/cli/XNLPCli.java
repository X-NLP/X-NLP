package com.xnlp.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xnlp.client.XNLPClient;
import com.xnlp.client.BenchmarkRequest;
import com.xnlp.client.XNLPClientException;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Command-line client for the X-NLP control plane. */
@Command(name = "xnlp", mixinStandardHelpOptions = true,
        description = "X-NLP CLI - model, dataset and evaluation workbench",
        subcommands = {
                XNLPCli.HealthCommand.class,
                XNLPCli.ListModelsCommand.class,
                XNLPCli.CapabilitiesCommand.class,
                XNLPCli.ProviderCommand.class,
                XNLPCli.BenchmarkCommand.class,
                XNLPCli.LoadCommand.class,
                XNLPCli.ActivateCommand.class,
                XNLPCli.UnloadCommand.class,
                XNLPCli.DeleteCommand.class,
                XNLPCli.PredictCommand.class,
                XNLPCli.DatasetListCommand.class,
                XNLPCli.EvaluationStartCommand.class,
                XNLPCli.EvaluationStatusCommand.class,
                XNLPCli.EvaluationCancelCommand.class
})
public class XNLPCli implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Option(names = {"-s", "--server"}, defaultValue = "http://localhost:8760",
            description = "X-NLP server URL")
    private String serverUrl;

    @Option(names = {"-k", "--api-key"}, description = "API key (or XNLP_API_KEY environment variable)")
    private String apiKey;

    @Option(names = {"-t", "--tenant"}, description = "Tenant ID (or XNLP_TENANT_ID environment variable)")
    private String tenantId;

    @Option(names = "--timeout-seconds", defaultValue = "60", description = "HTTP request timeout in seconds")
    private long timeoutSeconds;

    private XNLPClient client;

    private XNLPClient client() {
        if (client == null) {
            String resolvedApiKey = apiKey != null ? apiKey : System.getenv("XNLP_API_KEY");
            String resolvedTenant = tenantId != null ? tenantId : System.getenv("XNLP_TENANT_ID");
            client = XNLPClient.builder(serverUrl)
                    .apiKey(resolvedApiKey)
                    .tenantId(resolvedTenant)
                    .requestTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
        }
        return client;
    }

    @Override
    public Integer call() {
        System.out.println("X-NLP CLI - use --help to list commands");
        return 0;
    }

    @Command(name = "health", description = "Check server health")
    static class HealthCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;

        @Override
        public Integer call() {
            print(root.client().health());
            return 0;
        }
    }

    @Command(name = "list", aliases = "models", description = "List configured model profiles")
    static class ListModelsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Option(names = "--runtime", description = "List only models loaded in the runtime")
        boolean runtime;

        @Override
        public Integer call() {
            List<ModelInfo> models = runtime ? root.client().listRuntimeModels() : root.client().listModels();
            for (ModelInfo model : models) {
                System.out.printf("%-24s %-12s %-18s %-12s %s%n", model.getName(),
                        model.getType(), model.getProvider(), model.getStatus(), model.getModelName());
            }
            return 0;
        }
    }

    @Command(name = "capabilities", description = "List supported model protocols and providers")
    static class CapabilitiesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;

        @Override
        public Integer call() {
            print(root.client().modelCapabilities());
            return 0;
        }
    }


    @Command(name = "provider", description = "Inspect configured AI provider readiness",
            mixinStandardHelpOptions = true,
            subcommands = {ProviderStatusCommand.class, ProviderProbeCommand.class})
    static class ProviderCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;

        @Override
        public Integer call() {
            System.out.println("Use 'xnlp provider status' or 'xnlp provider probe'.");
            return 0;
        }
    }

    @Command(name = "status", description = "Show passive provider diagnostics", mixinStandardHelpOptions = true)
    static class ProviderStatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand ProviderCommand provider;

        @Override
        public Integer call() {
            print(provider.root.client().providerDiagnostics());
            return 0;
        }
    }

    @Command(name = "probe", description = "Actively probe configured providers", mixinStandardHelpOptions = true)
    static class ProviderProbeCommand implements Callable<Integer> {
        @CommandLine.ParentCommand ProviderCommand provider;

        @Override
        public Integer call() {
            print(provider.root.client().probeProviderDiagnostics());
            return 0;
        }
    }

    @Command(name = "benchmark", description = "Benchmark a loaded model", mixinStandardHelpOptions = true)
    static class BenchmarkCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Option(names = "--model", required = true, description = "Model profile name") String model;
        @Option(names = "--requests", defaultValue = "100", description = "Number of requests (1-10000)") int requests;
        @Option(names = "--concurrency", defaultValue = "4", description = "Concurrent workers (1-256)") int concurrency;
        @Option(names = "--text", defaultValue = "The future of natural language processing is bright.") String text;

        @Override
        public Integer call() {
            print(root.client().benchmark(model, new BenchmarkRequest(requests, concurrency, text)));
            return 0;
        }
    }

    @Command(name = "load", description = "Create or update a model profile")
    static class LoadCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0", description = "Profile name") String name;
        @Parameters(index = "1", description = "Provider model name") String modelName;
        @Option(names = "--type", defaultValue = "CHAT") String type;
        @Option(names = "--protocol", defaultValue = "SPRING_AI_CHAT") String protocol;
        @Option(names = "--provider", defaultValue = "spring-ai") String provider;
        @Option(names = "--base-url") String baseUrl;
        @Option(names = "--model-path") String modelPath;
        @Option(names = "--activate", description = "Load the profile into the runtime after saving") boolean activate;

        @Override
        public Integer call() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("name", name);
            config.put("modelName", modelName);
            config.put("modelPath", modelPath == null ? modelName : modelPath);
            config.put("type", type);
            config.put("protocol", protocol);
            config.put("provider", provider);
            if (baseUrl != null) config.put("baseUrl", baseUrl);
            ModelInfo saved = root.client().saveModel(config);
            if (activate) saved = root.client().activateModel(name);
            System.out.printf("%s: %s (%s)%n", activate ? "Activated" : "Saved", saved.getName(), saved.getStatus());
            return 0;
        }
    }

    @Command(name = "activate", description = "Load a saved CHAT profile into the runtime")
    static class ActivateCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0") String name;

        @Override
        public Integer call() {
            ModelInfo model = root.client().activateModel(name);
            System.out.printf("Activated: %s (%s)%n", model.getName(), model.getStatus());
            return 0;
        }
    }

    @Command(name = "unload", description = "Unload a runtime model but keep its profile")
    static class UnloadCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0") String name;

        @Override
        public Integer call() {
            root.client().unloadModel(name);
            System.out.println("Unloaded: " + name);
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a model profile and unload its runtime")
    static class DeleteCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0") String name;

        @Override
        public Integer call() {
            root.client().deleteModel(name);
            System.out.println("Deleted: " + name);
            return 0;
        }
    }

    @Command(name = "predict", description = "Run one inference request")
    static class PredictCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0") String name;
        @Parameters(index = "1") String text;

        @Override
        public Integer call() {
            PredictResponse response = root.client().predict(name, text);
            print(response);
            return 0;
        }
    }

    @Command(name = "dataset-list", description = "List evaluation datasets")
    static class DatasetListCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;

        @Override
        public Integer call() {
            root.client().listDatasets().forEach(dataset ->
                    System.out.printf("%-38s %-24s %5d entries%n", dataset.getId(), dataset.getName(), dataset.getEntryCount()));
            return 0;
        }
    }

    @Command(name = "evaluation-start", description = "Queue an asynchronous evaluation")
    static class EvaluationStartCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Option(names = "--model", required = true) String model;
        @Option(names = "--dataset", required = true) String dataset;
        @Option(names = "--task") String task;

        @Override
        public Integer call() {
            print(root.client().startEvaluation(model, dataset, task));
            return 0;
        }
    }

    @Command(name = "evaluation-status", description = "Show evaluation runs")
    static class EvaluationStatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Option(names = "--model") String model;
        @Option(names = "--dataset") String dataset;
        @Option(names = "--status") String status;

        @Override
        public Integer call() {
            print(root.client().listEvaluations(model, dataset, status));
            return 0;
        }
    }

    @Command(name = "evaluation-cancel", description = "Request cancellation of an evaluation")
    static class EvaluationCancelCommand implements Callable<Integer> {
        @CommandLine.ParentCommand XNLPCli root;
        @Parameters(index = "0") String id;

        @Override
        public Integer call() {
            print(root.client().cancelEvaluation(id));
            return 0;
        }
    }

    private static void print(Object value) {
        try {
            System.out.println(JSON.writeValueAsString(value));
        } catch (Exception e) {
            System.out.println(String.valueOf(value));
        }
    }

    static CommandLine createCommandLine() {
        return new CommandLine(new XNLPCli())
                .setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
                    PrintWriter error = commandLine.getErr();
                    if (exception instanceof XNLPClientException clientException) {
                        error.println("xnlp: " + clientException.getMessage());
                        if (clientException.getErrorCode() != null) {
                            error.println("error=" + clientException.getErrorCode());
                        }
                        if (clientException.getRequestId() != null) {
                            error.println("requestId=" + clientException.getRequestId());
                        }
                        if (clientException.getTraceId() != null) {
                            error.println("traceId=" + clientException.getTraceId());
                        }
                        return 2;
                    }
                    if (exception instanceof IllegalArgumentException) {
                        error.println("xnlp: " + exception.getMessage());
                        return 2;
                    }
                    error.println("xnlp: command failed");
                    return 1;
                });
    }

    public static void main(String[] args) {
        System.exit(createCommandLine().execute(args));
    }
}
