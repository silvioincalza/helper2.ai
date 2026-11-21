---
name: aiup-diagrams
description: Use whenever a diagram of the specification is created or changed in PlantUML or Mermaid (use case diagrams from /use-case-diagram, entity model ER diagrams from /entity-model, /reverse-engineer output, any ```mermaid or .puml under docs/). Adds the D2 twin and its rendered SVG that helper2.ai keeps for every diagram.
---

# D2 twin for every specification diagram

The AI Unified Process skills write PlantUML (`docs/use_cases.puml`, `docs/use_cases/<module>/use_cases.puml`) and
Mermaid (the ER diagrams in `docs/entity_model.md` and `docs/entity_model/<module>.md`). Those files stay the source
the process reads. helper2.ai also keeps each diagram in D2 with a committed SVG, like every other diagram of the
project, so GitHub shows it without a build step.

In the same change that creates or edits a PlantUML or Mermaid diagram:

1. Write or update its twin in `docs/architecture/diagrams/`:
   - `docs/use_cases.puml` → `use-cases-modules.d2`
   - `docs/use_cases/<module>/use_cases.puml` → `use-cases-<module>.d2`
   - the ER diagram of `docs/entity_model/<module>.md` → `entity-model-<module>.d2`
   - any other diagram → `<what-it-shows>.d2`, named like the files next to it
2. The twin shows the same things as its source, nothing added and nothing dropped: the same actors, use cases with
   their `UC-*` IDs, entities and relations. An ER twin shows relationships only, as the Mermaid does.
3. Start the `.d2` with what it shows and the file it mirrors. Use the one style of the project: import
   `...@style`, use only the classes `style.d2` defines (`person`, `app`, `ai`, `data`, `ext`, `group`, ...), the
   same layout engine as the diagrams next to it, and no inline `style.` override. A look `style.d2` cannot give is
   a change to `style.d2` for Silvio to approve, never a one-off.
4. Render it with `docs/architecture/diagrams/render.sh` and commit the `.d2` and the `.svg`. D2 writes SVG, not
   JPEG: the SVG is the image. Make a PNG only when Silvio asks for one, and do not commit it.
5. List the twin in the section "Specification (AI Unified Process)" of `docs/architecture/diagrams/README.md`,
   with its source file.
6. Check before you finish: the `.d2` compiles, and the count of actors, use cases, entities and relations is the
   same as in the source.

When a source diagram is deleted, delete its twin, its SVG and its README row in the same change.
