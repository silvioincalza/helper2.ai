import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** One copy button for a snippet or a command. */
export function CopyButton({ text, primary = false }: { text: string; primary?: boolean }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable: the text is still selectable */
    }
  }
  return (
    <button type="button" className={primary ? 'btn btn-primary' : 'btn btn-ghost'} onClick={() => void copy()}>
      {copied ? t('setup.copied') : t('setup.copy')}
    </button>
  );
}
