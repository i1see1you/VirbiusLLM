package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.virbius.groovy.l3.GroovyL3ValidationException;
import io.virbius.groovy.l3.GroovyL3Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "virbius-compiler", mixinStandardHelpOptions = true, version = "0.1.0-mvp")
public class CompilerCli implements Runnable {

    @Option(names = {"-i", "--input"}, required = true, description = "rule bundle yaml/json")
    private Path input;

    @Option(names = {"-o", "--output"}, required = true, description = "output directory")
    private Path output;

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) {
        int code = new CommandLine(new CompilerCli()).execute(args);
        System.exit(code);
    }

    @Override
    public void run() {
        try {
            JsonNode root = read(input);
            Files.createDirectories(output);
            int count = 0;
            JsonNode rules = root.get("rules");
            if (rules != null && rules.isArray()) {
                for (JsonNode rule : rules) {
                    String ruleId = rule.path("rule_id").asText("unknown");
                    if ("groovy".equals(rule.path("runtime").asText(""))) {
                        String body = "";
                        if (rule.has("body")) {
                            JsonNode b = rule.get("body");
                            body = b.isTextual() ? b.asText() : b.toString();
                        }
                        try {
                            GroovyL3Validator.validate(body);
                        } catch (GroovyL3ValidationException e) {
                            throw new CommandLine.ExecutionException(
                                    new CommandLine(this), "groovy rule " + ruleId + ": " + e.getMessage());
                        }
                    }
                    String layer = rule.path("layer").asText("edge");
                    Path out = output.resolve(layer + "-" + ruleId + ".json");
                    json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), rule);
                    count++;
                }
            }
            Path gwDir = output.resolve("gateway");
            GatewayApisixEmitter.emitService(root, gwDir, json);
            int routeCount = GatewayApisixEmitter.emitRoutes(root, gwDir, json);

            Path manifest = output.resolve("edge-manifest.json");
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            manifest.toFile(),
                            Map.of(
                                    "bundle_id",
                                    root.path("bundle_id").asText("poc-default"),
                                    "version",
                                    root.path("version").asText("0.1.0"),
                                    "rule_artifacts",
                                    count,
                                    "gateway_routes",
                                    routeCount));
            System.out.println(
                    "compiled " + count + " rules, " + routeCount + " gateway routes -> " + output);
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage(), e);
        }
    }

    private JsonNode read(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return yaml.readTree(Files.readString(path));
        }
        return json.readTree(Files.readString(path));
    }
}
