package ai.helper2.sources.huggingface;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class HubTasks {

  record Match(String pipelineTag, String phrase) {}

  static final Map<String, String> PHRASES = phrases();

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  private HubTasks() {}

  static Optional<Match> of(String text) {
    String padded =
        " " + NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
    return PHRASES.entrySet().stream()
        .filter(e -> padded.contains(" " + e.getKey() + " "))
        .findFirst()
        .map(e -> new Match(e.getValue(), e.getKey()));
  }

  static List<String> remaining(List<String> words, Match match) {
    List<String> phraseWords = List.of(match.phrase().split(" "));
    return words.stream().filter(w -> !partOfPhrase(w, phraseWords)).toList();
  }

  private static boolean partOfPhrase(String word, List<String> phraseWords) {
    String[] parts =
        NON_WORD.matcher(word.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().split(" ");
    return Arrays.stream(parts).allMatch(phraseWords::contains);
  }

  private static Map<String, String> phrases() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("automatic speech recognition", "automatic-speech-recognition");
    m.put("speech to text", "automatic-speech-recognition");
    m.put("speech recognition", "automatic-speech-recognition");
    m.put("text to speech", "text-to-speech");
    m.put("image segmentation", "image-segmentation");
    m.put("object detection", "object-detection");
    m.put("image classification", "image-classification");
    m.put("text to image", "text-to-image");
    m.put("image generation", "text-to-image");
    m.put("text to video", "text-to-video");
    m.put("depth estimation", "depth-estimation");
    m.put("transcription", "automatic-speech-recognition");
    m.put("segmentation", "image-segmentation");
    m.put("reranker", "text-ranking");
    m.put("rerankers", "text-ranking");
    m.put("reranking", "text-ranking");
    m.put("embedding", "feature-extraction");
    m.put("embeddings", "feature-extraction");
    m.put("summarization", "summarization");
    m.put("ocr", "image-to-text");
    return m;
  }
}
