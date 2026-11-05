import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

/**
 * No CSS animation repeats for ever (ADR 0042, decision 12; WCAG 2.2.2): each one ends within 5 seconds. The Home
 * radar is the one thing that keeps moving, and it moves by a JavaScript clock that stops on focus, under reduced
 * motion and with the account setting off (HomeRadar.test.tsx). Every stylesheet, and every inline style in a
 * component, is read as text.
 */
/** Vitest runs from frontend/. */
const SRC = join(process.cwd(), 'src');

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return files(path);
    return /\.(css|tsx?)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

/** The `animation` and `animation-iteration-count` declarations of a file that loop for ever. */
function loopingAnimations(text: string): string[] {
  const declarations = text.match(/animation(?:-iteration-count)?\s*[:=]\s*[^;}\n]*/gi) ?? [];
  const inlineStyles = text.match(/animation(?:IterationCount)?\s*:\s*['"`][^'"`]*['"`]/g) ?? [];
  return [...declarations, ...inlineStyles].filter((d) => /\binfinite\b/i.test(d));
}

describe('motion: no CSS animation loops', () => {
  it('the check finds a loop in each way it can be written', () => {
    expect(loopingAnimations('.a { animation: spin 1s linear infinite; }')).not.toEqual([]);
    expect(loopingAnimations('.a { animation-iteration-count: infinite }')).not.toEqual([]);
    expect(loopingAnimations("<div style={{ animation: 'spin 1s infinite' }} />")).not.toEqual([]);
    expect(
      loopingAnimations("<div style={{ animationIterationCount: 'infinite' }} />"),
    ).not.toEqual([]);
    expect(loopingAnimations('.a { animation: sweep 4.5s linear 1; }')).toEqual([]);
  });

  it('no stylesheet or component in src has an animation that repeats for ever', () => {
    const found = files(SRC).flatMap((file) =>
      loopingAnimations(readFileSync(file, 'utf8')).map(
        (d) => `${relative(SRC, file).split('\\').join('/')}: ${d.trim()}`,
      ),
    );
    expect(found).toEqual([]);
  });
});
