import { useTranslation } from 'react-i18next';
import type { TocEntry } from '@/features/pages/TextPage';
import { textOf, type TextEntry, type TextValues } from '@/features/pages/TextSections';

const HOW_STEPS = ['search', 'choose', 'check'] as const;
const RULES = ['notStated', 'dates', 'unchecked', 'fallback'] as const;

const HOW_ID = 'how-the-guide-answers';
const RULES_ID = 'never-guessed';

export function useHowItWorksToc(): TocEntry[] {
  const { t } = useTranslation();
  return [
    { id: HOW_ID, title: t('how.title') },
    { id: RULES_ID, title: t('how.rulesTitle') },
  ];
}

export function HowItWorks({ values }: { values?: TextValues }) {
  const { t } = useTranslation();
  return (
    <>
      <section id={HOW_ID} aria-labelledby={`${HOW_ID}-h`}>
        <h2 id={`${HOW_ID}-h`}>{t('how.title')}</h2>
        <p>{t('how.lede')}</p>
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
