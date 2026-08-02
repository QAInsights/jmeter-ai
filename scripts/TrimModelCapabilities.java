import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Trims models.dev's api.json down to the providers and capability fields
 * Feather Wand consumes. Usage:
 *   java TrimModelCapabilities <in.json> <out.json>
 *
 * Output per model (only fields that apply):
 *   reasoning: true          - supports_reasoning equivalent
 *   toggle:    true          - reasoning_options contains {"type":"toggle"}
 *   effort:    [...]         - reasoning_options {"type":"effort"} values
 *   budgetMin/budgetMax: n   - reasoning_options {"type":"budget_tokens"} range
 *   vision: true / pdf: true - from modalities.input
 */
public class TrimModelCapabilities {

    private static final Set<String> PROVIDERS = Set.of(
            "openai", "anthropic", "google", "xai", "deepseek", "amazon-bedrock", "meta");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java TrimModelCapabilities <in.json> <out.json>");
            System.exit(2);
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(args[0]));

        ObjectNode out = mapper.createObjectNode();
        out.put("source", "models.dev api.json");

        ObjectNode providersOut = out.putObject("providers");
        int total = 0;
        for (String provider : PROVIDERS) {
            JsonNode providerNode = root.path(provider).path("models");
            if (!providerNode.isObject()) {
                continue;
            }
            TreeMap<String, ObjectNode> models = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = providerNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> model = it.next();
                JsonNode entry = model.getValue();
                if (!entry.isObject()) {
                    continue;
                }
                ObjectNode caps = trimModel(entry, mapper);
                if (caps != null) {
                    models.put(model.getKey(), caps);
                }
            }
            if (!models.isEmpty()) {
                ObjectNode providerOut = providersOut.putObject(provider);
                models.forEach(providerOut::set);
                total += models.size();
            }
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(args[1]), out);
        System.out.println("Vendored " + total + " capable models -> " + args[1]);
    }

    /** Null when the model has no capability we consume. */
    private static ObjectNode trimModel(JsonNode entry, ObjectMapper mapper) {
        boolean reasoning = entry.path("reasoning").asBoolean(false);
        boolean vision = false;
        boolean pdf = false;
        for (JsonNode modality : entry.path("modalities").path("input")) {
            if ("image".equals(modality.asText())) {
                vision = true;
            }
            if ("pdf".equals(modality.asText())) {
                pdf = true;
            }
        }
        if (!reasoning && !vision && !pdf) {
            return null;
        }

        ObjectNode caps = mapper.createObjectNode();
        if (reasoning) {
            caps.put("reasoning", true);
        }
        for (JsonNode option : entry.path("reasoning_options")) {
            String type = option.path("type").asText("");
            if ("toggle".equals(type)) {
                caps.put("toggle", true);
            } else if ("effort".equals(type)) {
                ArrayNode values = caps.putArray("effort");
                for (JsonNode value : option.path("values")) {
                    values.add(value.asText());
                }
            } else if ("budget_tokens".equals(type)) {
                if (option.has("min")) {
                    caps.put("budgetMin", option.path("min").asLong());
                }
                if (option.has("max")) {
                    caps.put("budgetMax", option.path("max").asLong());
                }
            }
        }
        if (vision) {
            caps.put("vision", true);
        }
        if (pdf) {
            caps.put("pdf", true);
        }
        return caps;
    }
}
