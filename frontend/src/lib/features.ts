/**
 * Pages on the roadmap (docs/roadmap.md, Frontend) are hidden: no menu item, no links to them, and their paths go
 * home. Their code, tests and the API stay, so turning a flag on brings the page back.
 */
/** Saved (bookmarks): the menu item, the Save buttons and /saved. */
export const SAVED_ENABLED = false;

/**
 * Design v2 (ADR 0040), built page by page while this is off: shared components and tokens first, then Home, Results,
 * Resource, States, Saved and Sources, Mission and Privacy. The last step removes the flag and the old components.
 */
export const NEW_DESIGN_ENABLED = false;
