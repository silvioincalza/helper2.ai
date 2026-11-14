import { rowSourceNames } from './rowSources';

const source = (sourceId: string, sourceName: string) => ({ sourceId, sourceName });
const github = source('github', 'GitHub');
const catalog = source('helper2-index', 'helper2.ai catalog');
const byMeaning = source('helper2-semantic', 'helper2.ai catalog, by meaning');

describe('rowSourceNames', () => {
  it('names the catalog once when the row came from it by words and by meaning', () => {
    expect(rowSourceNames([github, catalog, byMeaning], 'Our catalog')).toEqual([
      'GitHub',
      'Our catalog',
    ]);
  });

  it('names the catalog the same way when the row came only by meaning', () => {
    expect(rowSourceNames([byMeaning, github], 'Our catalog')).toEqual(['Our catalog', 'GitHub']);
    expect(rowSourceNames([catalog], 'Our catalog')).toEqual(['Our catalog']);
  });

  it('keeps the other sources as the API names them, each once, in order', () => {
    expect(
      rowSourceNames([source('npm', 'npm registry'), github, source('github', 'GitHub')], 'x'),
    ).toEqual(['npm registry', 'GitHub']);
    expect(rowSourceNames([], 'x')).toEqual([]);
  });
});
