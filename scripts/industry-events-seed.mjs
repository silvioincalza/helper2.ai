#!/usr/bin/env node
import { readFileSync } from 'node:fs';

const NS = '6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12';

const q = (s) => (s === null || s === undefined ? 'NULL' : `'${String(s).replace(/'/g, "''")}'`);
const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const canonical = (u, edition) => {
  const x = new URL(u);
  return `https://${x.hostname.toLowerCase().replace(/^www\./, '')}${x.pathname.replace(/\/+$/, '')}?event=${slug(edition)}`;
};

const KINDS = ['CONFERENCE', 'HACKATHON', 'MEETUP', 'TALK', 'WORKSHOP'];
const FORMATS = ['IN_PERSON', 'ONLINE', 'HYBRID'];
const SEED_FIELDS = "('eventDates', 'eventFormat')";
const FINGERPRINT = 'industry-events-v1';
const TAG = 'industry-event';

const READ_ON = process.argv[2].match(/-(\d{4}-\d{2}-\d{2})\.json$/)?.[1];
if (!READ_ON) throw new Error('the research file name must end with the day its pages were read: industry-events-YYYY-MM-DD.json');
const READ_AT = q(`${READ_ON}T00:00:00Z`);

const researched = JSON.parse(readFileSync(process.argv[2], 'utf8'));
for (const e of researched) {
  if (!e.startDate || !e.officialUrl || !e.dateExcerpt) throw new Error(`${e.edition}: missing date, URL or excerpt`);
}
const isOver = (e) => (e.endDate ?? e.startDate) < READ_ON;
const events = researched.filter((e) => !isOver(e));
const out = [`INSERT INTO source (id, mode) VALUES ('curated', 'CURATED')
ON CONFLICT (id) DO NOTHING;
`];

for (const e of events) {
  const canon = canonical(e.officialUrl, e.edition);
  const rid = `uuid_generate_v5('${NS}'::uuid, ${q(canon)})`;
  const start = `${e.startDate}T00:00:00Z`;
  const end = e.endDate ? `${e.endDate}T00:00:00Z` : null;
  const stated = (s) => s && e.dateExcerpt.toLowerCase().includes(s.toLowerCase());
  const location = [e.city, stated(e.country) ? e.country : null].filter(Boolean).join(', ') || null;
  const kind = e.kind ?? 'CONFERENCE';
  if (!KINDS.includes(kind)) throw new Error(`${e.edition}: kind must be one of ${KINDS.join(', ')}`);
  const format = e.format ? e.format.toUpperCase().replace(/[\s-]+/g, '_') : null;
  if (format && !FORMATS.includes(format)) throw new Error(`${e.edition}: format must be one of ${FORMATS.join(', ')}`);
  const extra = [`'eventKind', ${q(kind)}`, format ? `'eventFormat', ${q(format)}` : null, location ? `'location', ${q(location)}` : null]
    .filter(Boolean).join(', ');
  out.push(`INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (${rid}, ${q(canon)}, ${q(e.officialUrl)}, ${q(e.edition)}, ${q(e.description)}, 'CURATED', 'EVENT', 'BOTH', 'CURATED', ${q(start)}, ${q(end)})
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();`);
  out.push(`DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = ${rid};`);
  out.push(`INSERT INTO resource_label (resource_id, kind, value) VALUES (${rid}, 'TAG', ${q(TAG)}) ON CONFLICT DO NOTHING;`);
  out.push(`INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', ${q(canon)}, ${rid}, ${q(e.dateUrl ?? e.officialUrl)}, ${q(canon)}, ${q(e.edition)}, ${q(e.description)}, 'EVENT',
        ${q(start)}, ${q(end)}, ${READ_AT}, ${q(FINGERPRINT)},
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', ${q(JSON.stringify([TAG]))}::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', ${extra}))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;`);
  out.push(`DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = ${rid} AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ${SEED_FIELDS});
DELETE FROM field_assertion WHERE resource_id = ${rid} AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ${SEED_FIELDS};`);
  out.push(`WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), ${rid}, 'eventDates', ${q(end ? `${e.startDate}/${e.endDate}` : e.startDate)}, 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), ${q(e.dateUrl ?? e.officialUrl)}, ${READ_AT}, ${q(e.dateExcerpt.slice(0, 500))}) RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;
`);
  if (format && e.formatExcerpt) {
    out.push(`WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), ${rid}, 'eventFormat', ${q(format)}, 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), ${q(e.dateUrl ?? e.officialUrl)}, ${READ_AT}, ${q(e.formatExcerpt.slice(0, 500))}) RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;
`);
  }
}

const listed = events.map((e) => q(canonical(e.officialUrl, e.edition))).join(',\n    ');
const stillListed = events.length ? ` AND external_id NOT IN (\n    ${listed})` : '';
out.push(`CREATE TEMPORARY TABLE dropped_event ON COMMIT DROP AS
SELECT resource_id, external_id FROM source_record
WHERE source_id = 'curated' AND fingerprint = ${q(FINGERPRINT)}${stillListed};
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    JOIN dropped_event d ON d.resource_id = a.resource_id
    WHERE a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ${SEED_FIELDS});
DELETE FROM field_assertion a USING dropped_event d
WHERE a.resource_id = d.resource_id AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ${SEED_FIELDS};
DELETE FROM source_record s USING dropped_event d WHERE s.source_id = 'curated' AND s.external_id = d.external_id;
DELETE FROM resource r USING dropped_event d
WHERE r.id = d.resource_id AND r.data_origin = 'CURATED' AND NOT EXISTS (SELECT 1 FROM source_record o WHERE o.resource_id = r.id);
`);

process.stdout.write(out.join('\n'));
