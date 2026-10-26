import { useId, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { IssueReportRequest } from '@/api/types';

const KINDS: IssueReportRequest['kind'][] = ['WRONG', 'OUTDATED', 'BROKEN_LINK', 'OTHER'];

export function ReportForm({ resourceId }: { resourceId: string }) {
  const { t } = useTranslation();
  const uid = useId();
  const [kind, setKind] = useState<IssueReportRequest['kind']>('WRONG');
  const [field, setField] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<'idle' | 'sending' | 'done' | 'failed'>('idle');
  const [failure, setFailure] = useState('');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const len = message.trim().length;
    if (len < 5 || len > 2000) {
      setError(t('report.errorLength'));
      return;
    }
    setError(null);
    setStatus('sending');
    try {
      await api.reportResource(resourceId, { kind, message: message.trim(), field: field.trim() || null });
      setStatus('done');
    } catch (err) {
      setFailure(err instanceof ApiError && err.problem?.detail ? err.problem.detail : t('report.errorGeneric'));
      setStatus('failed');
    }
  };

  if (status === 'done') {
    return <p role="status" className="banner banner-ok">{t('report.success')}</p>;
  }
  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="report-form">
      <div className="field">
        <label htmlFor={`${uid}-kind`}>{t('report.kind')}</label>
        <select id={`${uid}-kind`} value={kind} onChange={(e) => setKind(e.target.value as IssueReportRequest['kind'])}>
          {KINDS.map((k) => <option key={k} value={k}>{t(`report.kinds.${k}`)}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${uid}-field`}>{t('report.field')} <span className="muted">({t('common.optional')})</span></label>
        <input id={`${uid}-field`} value={field} maxLength={60} onChange={(e) => setField(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor={`${uid}-msg`}>{t('report.message')}</label>
        <textarea id={`${uid}-msg`} rows={4} value={message} maxLength={2000}
          aria-invalid={error ? true : undefined} aria-describedby={error ? `${uid}-err` : `${uid}-hint`}
          onChange={(e) => setMessage(e.target.value)} />
        <span id={`${uid}-hint`} className="muted small">{t('report.hint', { count: message.length })}</span>
        {error && <span id={`${uid}-err`} role="alert" className="field-error">{error}</span>}
      </div>
      {status === 'failed' && <p role="alert" className="field-error">{failure}</p>}
      <button type="submit" className="btn btn-primary" disabled={status === 'sending'}>{t('report.submit')}</button>
    </form>
  );
}
