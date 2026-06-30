package com.xnlp.cli;

import com.xnlp.client.XNLPClient;
import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "xnlp", mixinStandardHelpOptions = true,
         description = "X-NLP CLI - Unified NLP Model Serving Client")
public class XNLPCli implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, defaultValue = "http://localhost:8760",
            description = "X-NLP server URL")
    private String serverUrl;

    private XNLPClient client;

    private XNLPClient client() {
        if (client == null) {
            client = new XNLPClient(serverUrl);
        }
        return client;
    }

    @Override
    public Integer call() {
        System.out.println("X-NLP CLI - use subcommands: list, load, unload, predict, health");
        return 0;
    }

    @Command(name = "health", description = "Check server health")
    int health() throws Exception {
        Map<String, Object> resp = client().health();
        System.out.println(resp);
        return 0;
    }

    @Command(name = "list", description = "List loaded models")
    int list() throws Exception {
        var models = client().listModels();
        for (ModelInfo m : models) {
            System.out.printf("%s  %s  %s  %s%n",
                    m.getName(), m.getVersion(), m.getBackend(), m.getStatus());
        }
        return 0;
    }

    @Command(name = "load", description = "Load a model")
    int load(
        @Parameters(index = "0", description = "Model name") String name,
        @Parameters(index = "1", description = "Model path") String path,
        @Option(names = {"-b", "--backend"}, defaultValue = "auto") String backend
    ) throws Exception {
        Map<String, Object> config = Map.of(
                "name", name,
                "model_path", path,
                "backend", backend);
        ModelInfo info = client().loadModel(config);
        System.out.printf("Loaded: %s  %s  %s  %s%n",
                info.getName(), info.getVersion(), info.getBackend(), info.getStatus());
        return 0;
    }

    @Command(name = "unload", description = "Unload a model")
    int unload(@Parameters(index = "0", description = "Model name") String name)
            throws Exception {
        client().unloadModel(name);
        System.out.println("Unloaded: " + name);
        return 0;
    }

    @Command(name = "predict", description = "Run inference")
    int predict(
        @Parameters(index = "0", description = "Model name") String name,
        @Parameters(index = "1", description = "Input text") String text
    ) throws Exception {
        PredictResponse resp = client().predict(name, text);
        System.out.println(resp);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new XNLPCli()).execute(args);
        System.exit(exitCode);
    }
}
