package ai.helper2.platform.llm;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reading what a model wrote. Reasoning models (Nemotron) may put their thinking in {@code <think>}
 * blocks before the answer; that text can contain braces and stray lines, so it is removed before
 * anything else looks at the answer.
 */
public final class ModelAnswers {

    /** A closed block, or an unclosed one: the model ran out of tokens while thinking, so no answer follows. */
    private static final Pattern REASONING = Pattern.compile("(?s)<think>.*?(</think>|\\z)");

    private ModelAnswers() {
    }

    /** The answer without reasoning blocks, stripped; empty when there was only reasoning or nothing. */
    public static String withoutReasoning(String answer) {
        return answer == null ? "" : REASONING.matcher(answer).replaceAll("").strip();
    }

    /**
     * The outermost JSON object of the answer, from its first "{" to its last "}" after reasoning is removed.
     * Lenient on wrapping text (a preamble, code fences); whether the object is valid is the parser's job.
     */
    public static Optional<String> jsonObject(String answer) {
        String text = withoutReasoning(answer);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start < 0 || end < start ? Optional.empty() : Optional.of(text.substring(start, end + 1));
    }
}
