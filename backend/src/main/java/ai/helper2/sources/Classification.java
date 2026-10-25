package ai.helper2.sources;

import ai.helper2.shared.ResourceType;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, documented rules (no LLM) used by general-purpose sources (GitHub, npm):
 * an AI-relevance gate, resource-type rules from topics/keywords, and OSI license detection.
 */
final class Classification {

    private static final Pattern AI_TERMS = Pattern.compile(
        "\\b(ai|a\\.i\\.|llm|llms|gpt|genai|generative|mcp|model context protocol|agent|agents|agentic|machine[- ]learning|"
            + "deep[- ]learning|ml|neural|transformer|transformers|openai|anthropic|claude|gemini|ollama|llama|mistral|"
            + "langchain|llamaindex|rag|embedding|embeddings|diffusion|chatbot|prompt|prompts|copilot|nlp|vector)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> OSI = Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "GPL-2.0",
        "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0", "MPL-2.0", "ISC", "Unlicense", "EPL-2.0", "0BSD", "Zlib", "Artistic-2.0");

    private Classification() {
    }

    static boolean isAiRelated(String title, String description, Collection<String> labels) {
        String text = String.join(" ", title == null ? "" : title.replace('-', ' ').replace('_', ' '),
            description == null ? "" : description, String.join(" ", labels).replace('-', ' '));
        return AI_TERMS.matcher(text).find();
    }

    static ResourceType typeOf(String title, String description, Collection<String> labels, ResourceType fallback) {
        String all = (String.join(" ", labels) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (all.contains("mcp-server") || all.contains("mcp") || all.contains("model-context-protocol")
            || all.contains("modelcontextprotocol") || desc.contains("model context protocol") || desc.contains("mcp server")) {
            return ResourceType.MCP_SERVER;
        }
        if (all.contains("agent-skill") || all.contains("claude-skills") || all.contains("skills")) {
            return ResourceType.SKILL;
        }
        if (all.contains("prompt")) {
            return ResourceType.PROMPT;
        }
        if (all.contains("agent")) {
            return ResourceType.AGENT;
        }
        return fallback;
    }

    /** true only for an OSI-approved SPDX id; null when unknown (no license is NOT "closed", just unknown). */
    static Boolean openSource(String spdx) {
        return spdx != null && OSI.contains(spdx) ? Boolean.TRUE : null;
    }
}
