package ai.helper2.sources.classifier;

/**
 * UC-030 BR-013: says whether a page is the page of the item a list entry names, where the check by
 * code cannot decide. It answers yes or no and writes no text.
 */
public interface ItemPageJudge {

  enum Answer {
    YES,
    NO,
    /** No answer, or one that is neither yes nor no. */
    NONE
  }

  Answer isPageOf(String name, String pageText);

  /** Never asks a model: every page the code cannot decide gives nothing. */
  ItemPageJudge NOT_ASKED = (name, pageText) -> Answer.NONE;
}
