import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** One copy button for a snippet, a command or a prompt: [label] until it copied, then "Copied" for two seconds. */
export function CopyButton({ text, label, primary = false }: { text: string; label?: string; primary?: boolean }) {
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
      {copied ? t('common.copied') : (label ?? t('common.copy'))}
    </button>
  );
}
