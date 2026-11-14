import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { copyFileSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./industry-events-seed.mjs', import.meta.url));
const research = fileURLToPath(new URL('../docs/research/', import.meta.url));
const seed = fileURLToPath(new URL('../backend/libs/schema/src/main/resources/db/migration/R__industry_events.sql', import.meta.url));
const industryFiles = readdirSync(research).filter((name) => /^industry-events-\d{4}-\d{2}-\d{2}\.json$/.test(name));

test('one industry research file exists, named by the day its pages were read', () => {
  assert.equal(industryFiles.length, 1);
});

test('the industry seed is what the script writes from the research file', () => {
  const written = execFileSync('node', [script, join(research, industryFiles[0])], { encoding: 'utf8' });

  assert.equal(written, readFileSync(seed, 'utf8'));
});

test('the seed says the pages were read on the day in the file name', () => {
  const day = industryFiles[0].match(/\d{4}-\d{2}-\d{2}/)[0];
  const retrieved = new Set(readFileSync(seed, 'utf8').match(/'\d{4}-\d{2}-\d{2}T00:00:00Z', 'industry-events-v1'/g));

  assert.deepEqual([...retrieved], [`'${day}T00:00:00Z', 'industry-events-v1'`]);
});

test('a research file with no day in its name is refused', () => {
  const undated = join(mkdtempSync(join(tmpdir(), 'events-')), 'industry-events.json');
  copyFileSync(join(research, industryFiles[0]), undated);

  const run = spawnSync('node', [script, undated], { encoding: 'utf8' });

  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /the day its pages were read/);
});

test('an event that is over on the day in the file name is not written, and the seed removes it', () => {
  const edition = (name, startDate, endDate) => ({
    edition: name,
    officialUrl: `https://example.org/${name}`,
    startDate,
    endDate,
    dateExcerpt: `${name}, ${startDate}`,
  });
  const file = join(mkdtempSync(join(tmpdir(), 'events-')), 'industry-events-2027-03-10.json');
  writeFileSync(
    file,
    JSON.stringify([
      edition('over', '2027-03-08', '2027-03-09'),
      edition('one-day-over', '2027-03-09', null),
      edition('last-day', '2027-03-08', '2027-03-10'),
      edition('one-day-today', '2027-03-10', null),
      edition('next', '2027-04-01', '2027-04-02'),
    ]),
  );

  const written = execFileSync('node', [script, file], { encoding: 'utf8' });
  const kept = written.slice(written.indexOf('external_id NOT IN'), written.indexOf('DELETE FROM evidence WHERE id IN (\n    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id\n    JOIN dropped_event'));

  assert.doesNotMatch(written, /event=over'|event=one-day-over'/);
  assert.deepEqual(kept.match(/event=[a-z-]+/g), ['event=last-day', 'event=one-day-today', 'event=next']);
  assert.equal(written.match(/INSERT INTO resource \(/g).length, 3);
});

test('when every event in the file is over, nothing is written and the seed removes every seeded event', () => {
  const file = join(mkdtempSync(join(tmpdir(), 'events-')), 'industry-events-2027-03-10.json');
  writeFileSync(
    file,
    JSON.stringify([
      { edition: 'over', officialUrl: 'https://example.org/over', startDate: '2027-03-08', endDate: '2027-03-09', dateExcerpt: 'over, 2027-03-08' },
    ]),
  );

  const written = execFileSync('node', [script, file], { encoding: 'utf8' });

  assert.doesNotMatch(written, /NOT IN \(\s*\)/);
  assert.doesNotMatch(written, /INSERT INTO resource \(/);
  assert.match(written, /WHERE source_id = 'curated' AND fingerprint = 'industry-events-v1';\nDELETE FROM evidence/);
});

test('the committed research file lists no event that is over', () => {
  const day = industryFiles[0].match(/\d{4}-\d{2}-\d{2}/)[0];
  const events = JSON.parse(readFileSync(join(research, industryFiles[0]), 'utf8'));

  assert.deepEqual(events.filter((event) => (event.endDate ?? event.startDate) < day).map((event) => event.edition), []);
});
