import axe from 'axe-core';

/** Runs axe on a container. color-contrast is disabled: jsdom has no layout/CSS engine to compute it. */
export async function axeViolations(container: Element) {
  // axe probes canvas for some rules; jsdom logs noisy 'not implemented' errors otherwise.
  HTMLCanvasElement.prototype.getContext = (() => null) as typeof HTMLCanvasElement.prototype.getContext;
  const res = await axe.run(container, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  return res.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join('; ')})`);
}
