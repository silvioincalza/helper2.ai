# Mockups

What the screens of a use case look like. A use case links its mockups on its `**Mockup:**` line.

| Path | What |
|---|---|
| `uc-*.md`, `back-office.md` | Screen notes per use case: what the page shows, step by step and in each state (loading, success, empty, partial, error). The behaviour is in the use case; the look is in the design system ([frontend/design](../../frontend/design/README.md), [ADR 0040](../architecture/decisions/ADR-0040-design-system.md)). |
| `images/` | Still pictures a screen note links, named after its use case. |
| `boards/` | The boards of every page, its states and the dark theme: one `.dc.html` per board, laid out by `canvas.json`. They open in the private design canvas they come from; in a browser alone they show without its script. No use case links them. |
