# AI literacy

The EU AI Act (Art. 4) asks providers and deployers of AI systems to make sure the people who build and run them
know enough about AI for the job. This page says who that is for helper2.ai, what they know and how they keep up.

## Who builds and runs the AI parts

One person: Silvio Incalza, the operator. He designs, builds, deploys and watches every AI feature: the guide,
the Scout's enrichment, query translation, search by meaning and the setup guide. Nobody else operates them, and no
user is asked to make decisions about the models.

## What he knows and checks

- **What the models are.** The guide and the Scout use NVIDIA Nemotron models on Nebius Token Factory; search by
  meaning uses NVIDIA's embedding model. Only NVIDIA models are allowed: the app does not start with another model id
  ([ADR 0008](adr/foundation/0008-llm-integration.md)). No model is trained or changed by helper2.ai.
- **What they get wrong.** A model can invent tools, quotes and dates. So the model never decides what is true: it may
  only pick among retrieved candidates, every pick needs a quote found word for word in the candidate's own text, and
  a second call checks each reason against its quote ([README](../README.md#how-the-models-are-used)).
  The Scout's claims need evidence, model dates are never accepted, and a disagreement with a source goes to human
  review.
- **How good the answers are.** A fixed set of questions and a set of 13 hostile ones run against staging every
  night ([ADR 0046](adr/operations/0046-evaluation-guide-and-search.md)). Search ranking
  has its own offline evaluation ([ADR 0046](adr/operations/0046-evaluation-guide-and-search.md)).
- **What it costs and when it stops.** Daily caps on model calls and web searches, and circuit breakers on every
  model call ([ADR 0032](adr/guide/0032-guide-cost-guard-and-answer-cache.md), [ADR 0006](adr/foundation/0006-outbound-calls-and-resilience.md)).
- **What users are told.** Every text a model wrote has a visible label and a machine-readable mark
  ([ADR 0033](adr/guide/0033-marking-ai-written-text-research-trail.md)). The Mission page explains them.

## How he keeps up

- The nightly evaluation report shows when the guide gets worse. Before a model change, the evaluation runs on
  staging with the new model.
- Before a new model or provider, he reads its model card and terms, and updates the Privacy page in the same change.
- The AI regulation gap analysis is re-checked when the AI Act, its guidance or the Code of Practice change, and when
  the Swiss AI act goes to consultation.
- Fine-tuning or changing a model needs a new scope check first: it could make helper2.ai a general-purpose AI model
  provider.
