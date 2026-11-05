import { useTranslation } from 'react-i18next';
import type { TocEntry } from '@/components/TextPage';
import { textOf, type TextEntry, type TextValues } from '@/components/TextSections';

const HOW_STEPS = ['search', 'choose', 'check'] as const;
const RULES = ['notStated', 'dates', 'unchecked', 'fallback'] as const;

/** The ids of the two sections below, for "On this page". */
const HOW_ID = 'how-the-guide-answers';
const RULES_ID = 'never-guessed';

/** The two sections of [HowItWorks] for "On this page", in their order on the page. */
export function useHowItWorksToc(): TocEntry[] {
  const { t } = useTranslation();
  return [
    { id: HOW_ID, title: t('how.title') },
    { id: RULES_ID, title: t('how.rulesTitle') },
  ];
}

/**
 * How the guide answers, and the honesty rules. Shown on the mission page, as prose like the sections above. The
 * number of candidates and picks come from GET /policy ([values]); without them the steps say it in words.
 */
export function HowItWorks({ values }: { values?: TextValues }) {
  const { t } = useTranslation();
  return (
    <>
      <section id={HOW_ID} aria-labelledby={`${HOW_ID}-h`}>
        <h2 id={`${HOW_ID}-h`}>{t('how.title')}</h2>
        <p>{t('how.lede')}</p>
        {/* A real sequence: retrieval, then the model, then the validator. */}
        <ol className="h2-steps">
          {HOW_STEPS.map((k) => (
            <li key={k}>
              <h3>{t(`how.steps.${k}.title`)}</h3>
              <p>
                {textOf(
                  t(`how.steps.${k}.body`, { returnObjects: true, ...values }) as TextEntry,
                  values,
                )}
              </p>
            </li>
          ))}
        </ol>
      </section>
      <section id={RULES_ID} aria-labelledby={`${RULES_ID}-h`}>
        <h2 id={`${RULES_ID}-h`}>{t('how.rulesTitle')}</h2>
        <ul>
          {RULES.map((k) => (
            <li key={k}>
              <strong>{t(`how.rules.${k}.title`)}.</strong> {t(`how.rules.${k}.body`)}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
