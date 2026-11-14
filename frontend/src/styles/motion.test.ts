import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const SRC = join(process.cwd(), 'src');

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return files(path);
    return /\.(css|tsx?)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

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
