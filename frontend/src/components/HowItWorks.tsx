import { useTranslation } from 'react-i18next';

const HOW_STEPS = ['search', 'choose', 'check'] as const;
const RULES = ['notStated', 'dates', 'unchecked', 'fallback'] as const;

/** How the guide answers, and the honesty rules. Two parts of the mission page. */
export function HowItWorks() {
  const { t } = useTranslation();
  return (
    <>
      <section aria-labelledby="how-h" className="page-part">
        <h2 id="how-h">{t('how.title')}</h2>
        <p>{t('how.lede')}</p>
        {/* A real sequence: retrieval, then the model, then the validator. */}
        <ol className="how-steps">
          {HOW_STEPS.map((k, i) => (
            <li key={k} className="how-step">
              <span className="how-step__n" aria-hidden="true">{i + 1}</span>
              <h3>{t(`how.steps.${k}.title`)}</h3>
              <p>{t(`how.steps.${k}.body`)}</p>
            </li>
          ))}
        </ol>
      </section>
      <section aria-labelledby="rules-h" className="page-part">
        <h2 id="rules-h">{t('how.rulesTitle')}</h2>
        <ul className="rules">
          {RULES.map((k) => (
            <li key={k} className="rule">
              <h3>{t(`how.rules.${k}.title`)}</h3>
              <p>{t(`how.rules.${k}.body`)}</p>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
