import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { Assertion, ModelFamily } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { InfoTip } from '@/components/InfoTip';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { formatDate, safeHref } from '@/lib/format';
import { askParams } from '@/lib/searchState';
import { BENCHMARK_PREFIX, SPEC_ROWS, benchmarkRows, bestIn, formatSpec, spec } from '@/lib/modelCompare';

const MAX_COMPARED = 4;
const DEFAULT_COMPARED = ['openai', 'anthropic', 'google', 'xai'];

function SpecCell({ field, a, best }: { field: string; a: Assertion | undefined; best: boolean }) {
  const { t, i18n } = useTranslation();
  if (!a) return <td className="muted">{t('models.notStated')}</td>;
  const ev = a.evidence[0];
  const read = ev ? t('models.readOn', { date: formatDate(ev.retrievedAt, i18n.language) }) : '';
  return (
    <td className={best ? 'spec-best' : undefined}>
      <span>{field === 'openWeights' && (a.value === 'true' || a.value === 'false')
        ? t(a.value === 'true' ? 'common.yes' : 'common.no')
        : formatSpec(field, a.value, i18n.language)}</span>
      {best && <span className="sr-only"> ({t('models.best')})</span>}
      {ev && safeHref(ev.sourceUrl) && (
        <a className="spec-src small" href={safeHref(ev.sourceUrl)} target="_blank" rel="noopener noreferrer"
          title={[ev.excerpt ? `“${ev.excerpt}”` : '', read].filter(Boolean).join(' · ')}>
          {t('models.source')}<span className="sr-only"> ({read})</span>
        </a>
      )}
    </td>
  );
}

/**
 * "Not sure which model fits?" Ready questions only: each opens the guide on the home page through the URL
 * (/?q=…&ask=…). Asking in your own words happens in the one box in the header; this page adds no second field.
 */
function GuideExamples({ examples }: { examples: string[] }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="ask-guide-h" className="panel ask-guide">
      <h2 id="ask-guide-h" className="h-small">{t('askGuide.title')}</h2>
      <p className="muted small">{t('askGuide.lede')}</p>
      <ul className="plain-list inline-list" aria-label={t('askGuide.examples')}>
        {examples.map((x) => (
          <li key={x}><Link className="chip chip-ghost" to={{ pathname: '/', search: askParams(x).toString() }}>{x}</Link></li>
        ))}
      </ul>
    </section>
  );
}

export default function ModelsPage() {
  const { t, i18n } = useTranslation();
  useTitle(t('models.title'));
  const q = useApi('model-families', (s) => api.listModelFamilies(s));
  const [params, setParams] = useSearchParams();
  const families = q.data?.families ?? [];
  const requested = params.get('compare')?.split(',').filter(Boolean);
  const selectedIds = (requested?.length ? requested : DEFAULT_COMPARED).filter((id) => families.some((f) => f.id === id)).slice(0, MAX_COMPARED);
  const selected = selectedIds.map((id) => families.find((f) => f.id === id)).filter((f): f is ModelFamily => f !== undefined);

  const toggle = (id: string) => {
    const next = selectedIds.includes(id) ? selectedIds.filter((x) => x !== id) : [...selectedIds, id].slice(-MAX_COMPARED);
    setParams(next.length ? { compare: next.join(',') } : {}, { replace: true });
  };

  const rows = [...SPEC_ROWS, ...benchmarkRows(selected)];
  const rowLabel = (field: string) =>
    field.startsWith(BENCHMARK_PREFIX) ? field.slice(BENCHMARK_PREFIX.length) : t(`models.field.${field}`);

  return (
    <div>
      <h1 className="title-with-tip">
        {t('models.title')} <InfoTip label={t('models.about')}>{t('models.lede')}</InfoTip>
      </h1>
      {q.loading && <p role="status">{t('common.loading')}</p>}
      {!q.loading && q.error !== undefined && (
        <div role="alert" className="state-box state-error">
          <p>{t('models.error')}</p>
          <button type="button" className="btn btn-primary" onClick={q.reload}>{t('common.retry')}</button>
        </div>
      )}
      {q.data && (
        <>
          <fieldset className="family-picker">
            <legend className="filter-legend">{t('models.pick', { max: MAX_COMPARED })}</legend>
            {families.map((f) => (
              <label key={f.id} className={`family-chip${selectedIds.includes(f.id) ? ' is-on' : ''}`}>
                <input type="checkbox" checked={selectedIds.includes(f.id)} onChange={() => toggle(f.id)} />
                {f.name} <span className="muted small">{f.provider}</span>
              </label>
            ))}
          </fieldset>

          {selected.length === 0 ? (
            <p className="state-box">{t('models.pickSome')}</p>
          ) : (
            <div className="table-wrap compare-wrap">
              <table className="compare">
                <caption className="sr-only">{t('models.caption')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('models.spec')}</th>
                    {selected.map((f) => (
                      <th scope="col" key={f.id}>
                        <Link to={`/resource/${f.card.id}`}>{f.name}</Link>
                        <div className="muted small">{f.provider}</div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((field) => {
                    const best = bestIn(selected, field);
                    return (
                      <tr key={field}>
                        <th scope="row">{rowLabel(field)}</th>
                        {selected.map((f) => <SpecCell key={f.id} field={field} a={spec(f, field)} best={best.has(f.id)} />)}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <p className="muted small">{t('models.benchmarkNote')}</p>
            </div>
          )}

          <GuideExamples examples={[
            ...(selected.length >= 2 ? [t('askGuide.compareExample', { a: selected[0]?.name, b: selected[1]?.name })] : []),
            t('askGuide.example1'),
            t('askGuide.example2'),
          ]} />

          {selected.map((f) => (
            <section key={f.id} className="panel" aria-labelledby={`listed-${f.id}`}>
              <h2 id={`listed-${f.id}`} className="h-small">{t('models.newest', { family: f.name })}</h2>
              {f.listedModels.length === 0 ? (
                <p className="muted small">{t('models.noneListed')}</p>
              ) : (
                <ul className="plain-list listed-models">
                  {f.listedModels.map((m) => (
                    <li key={m.resourceId}>
                      <Link to={`/resource/${m.resourceId}`}>{m.title}</Link>{' '}
                      <span className="muted small">
                        {[
                          m.published && formatDate(m.published, i18n.language),
                          t('models.via', { source: m.sourceId }),
                        ].filter(Boolean).join(' · ')}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              <ExternalLink url={f.card.url}>{t('models.officialPage')}</ExternalLink>
            </section>
          ))}
        </>
      )}
    </div>
  );
}
