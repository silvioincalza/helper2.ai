# 0016 — Guide reason check: a second model call checks each reason; `checked` event

**Status.** Accepted (2026-10-27). **Builds on:** [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md)

## Context
The evidence gate ([0015](0015-ai-guide-evidence-gate.md)) proves that a pick's quote exists in the resource's own
text. It does not prove the reason. A reason can still add a claim the quote does not support, for example "free",
"MIT", "runs offline" or a feature the text never mentions. Code cannot compare the meaning of free text in the user's
language; a model can, but its answer is a judgment, not a proof.

## Decision
1. **When.** After `GuideValidator`, if at least one pick was kept. With no kept pick there is no check and no
   `checked` event (the answer is `PARTIAL` with search results).
2. **Input.** One `ReasonClaim` per kept pick: key, reason, quote, the candidate's text, and only the constraint checks
   that code already CONFIRMED. All picks go in one call.
3. **Call.** `SpringAiGuideModel.verifyReasons`: reasoning model, prompt `prompts/guide/verify-reasons.txt`, thinking
   off (`H2_GUIDE_THINK_VERIFY`, default false), 4096 output tokens, native JavaScript Object Notation (JSON) schema when `H2_NEBIUS_NATIVE_SCHEMA`
   is true. Evidence text goes through `UntrustedText` and the prompt says to ignore instructions inside it. The answer
   is `{"verdicts": [{"key": "c3", "supported": true}]}`. Unknown keys are dropped; a key given twice must be true twice.
4. **Verdicts** (`ReasonCheck`):
   - `SUPPORTED`: every factual claim of the reason is in its evidence. The reason is shown.
   - `REMOVED`: some claim is not. The reason is blanked; the pick and its quote stay, because the quote passed the gate.
   - `NOT_CHECKED`: no verdict for that key, the call failed, or the guide's daily budget is spent. The page says the
     reason was not checked. A failure never removes a pick.
5. **Event.** `GuideProgress.reasonsChecked` sends `checked` with the counts `supported`, `removed`, `notChecked`.
6. **Cache rule.** An answer with any artificial intelligence (AI) pick left `NOT_CHECKED` is not cached ([0017](0017-guide-cost-guard.md)), so an
   unchecked reason is not served for 24 hours.
7. **Budget.** The call takes units from the guide budget like plan and choose ([0014](0014-llm-integration.md)), so a
   fresh question costs 3 calls, more with retries.

## Consequences
- A reason that adds price, license, hosting or feature claims is caught most of the time. Evaluation of 2026-10-27:
  30 reasons supported, 1 removed, 0 not checked ([0030](../quality/0030-guide-evaluation.md)).
- It is a model's judgment and can miss a claim or reject a correct one. The quote stays the hard guarantee.
- One more model call per question: more cost and latency (reason check 0.5–3.7 s on 2026-10-27, from
  [docs/evaluation.md](../../evaluation.md)). The daily cap of 600 calls covers at most 200 fresh questions.
- Blanking a reason keeps a useful pick visible without an unsupported claim.

## Alternatives considered
- No check — unsupported claims in reasons reach the user.
- Code-only check (keyword list of risky words) — misses paraphrases and other languages; blocks harmless reasons.
- Ask the chooser to only write supported reasons — the prompt already says so; the check catches the cases where it does not.
- Drop the whole pick on REMOVED — loses a pick whose quote is proven; blanking the reason is enough.
- One call per pick — more calls and latency for the same result.

## Code
- `backend/src/main/kotlin/ai/helper2/guide/usecase/ReasonCheckStep.kt` — `verdicts`; `guide/Guide.kt` — `ReasonClaim`, `ReasonCheck`.
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` — `verifyReasons`.
- `backend/src/main/resources/prompts/guide/verify-reasons.txt` — the checker's instructions.
- `backend/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — `checked` event.
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `GuideAnswerCache.isCacheable`.

## Diagrams
[flow](../../diagrams/flow-0016-guide-reason-check.svg) · [sequence](../../diagrams/sequence-0016-guide-reason-check.svg)

## Roadmap
- [The reason check is a model judgment and can miss a claim](../../roadmap.md#5-ai-quality-and-cost)
