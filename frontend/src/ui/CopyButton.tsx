import { useState } from 'react';
import { useTranslation } from 'react-i18next';

export function CopyButton({ text, primary = false }: { text: string; primary?: boolean }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {}
  }
  return (
    <button
      type="button"
      className={primary ? 'btn btn-primary' : 'btn btn-ghost'}
      onClick={() => void copy()}
    >
      {copied ? t('common.copied') : t('common.copy')}
    </button>
  );
}
