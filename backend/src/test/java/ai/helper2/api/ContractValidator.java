package ai.helper2.api;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates real API responses against the OpenAPI 3.1 component schemas (which are JSON Schema
 * 2020-12). This is what makes the contract binding on the backend, not just on generated types.
 */
final class ContractValidator {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SPEC = YAMLMapper.builder().build()
        .readTree(new File(System.getProperty("openapi.spec", "../api/openapi.yaml")));
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private ContractValidator() {
    }

    static void assertMatches(String schemaName, String body) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + schemaName);
        root.set("components", SPEC.get("components"));
        Schema schema = REGISTRY.getSchema(root);
        List<Error> errors = schema.validate(JSON.readTree(body));
        if (!errors.isEmpty()) {
            throw new AssertionError("Response violates contract schema " + schemaName + ":\n"
                + errors.stream().map(Error::toString).sorted().collect(Collectors.joining("\n")));
        }
    }

    static void assertArrayMatches(String itemSchemaName, String body) {
        for (JsonNode item : JSON.readTree(body)) {
            assertMatches(itemSchemaName, JSON.writeValueAsString(item));
        }
    }
}
