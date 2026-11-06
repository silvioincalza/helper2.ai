import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ConfigSetting } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDuration } from '@/lib/duration';

/** One value in words, by its kind; ids and expressions in code style. */
function SettingValue({ setting, t }: { setting: ConfigSetting; t: TFunction }) {
  const { kind, value, unit } = setting;
  switch (kind) {
    case 'FLAG':
      return <>{value === 'true' ? t('admin.settings.on') : t('admin.settings.off')}</>;
    case 'CAP':
      if (value === null) return <>{t('admin.settings.noCap')}</>;
      return <>{unit ? `${value} ${unit}` : value}</>;
    case 'COUNT':
      return <>{unit ? `${value} ${unit}` : value}</>;
    case 'DURATION':
      return <>{value === null ? t('admin.settings.none') : formatDuration(Number(value), t)}</>;
    case 'NAMES':
      return <>{value ? value.split(',').join(', ') : t('admin.settings.none')}</>;
    default:
      return value === null ? <>{t('admin.settings.none')}</> : <code>{value}</code>;
  }
}

/**
 * The settings the app runs with now (ADR 0045), grouped by module, read-only. Each row: the setting
 * in words, its value, and the variable that sets it. The server never sends a secret.
 */
export default function SettingsPage() {
  const { t } = useTranslation();
  const config = useApi('admin/config', (s) => api.getEffectiveConfig(s), { cache: false });
  const { retry, focusRef: heading } = useRetry(config.reload, config.loading);
  const groups = config.data?.groups ?? [];

  return (
    <section aria-labelledby="settings-h" className="h2-admin__part">
      <h2 id="settings-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.settings.title')}
      </h2>
      <p className="muted">{t('admin.settings.lede')}</p>
      {config.loading ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : config.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.settings.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : (
        groups.map((g) => (
          <section key={g.id} aria-labelledby={`settings-${g.id}`}>
            <h3 id={`settings-${g.id}`} className="h2-admin__h">
              {g.title}
            </h3>
            <div className="table-wrap">
              <table aria-labelledby={`settings-${g.id}`}>
                <thead>
                  <tr>
                    <th scope="col">{t('admin.settings.col.setting')}</th>
                    <th scope="col">{t('admin.settings.col.value')}</th>
                    <th scope="col">{t('admin.settings.col.variable')}</th>
                  </tr>
                </thead>
                <tbody>
                  {g.settings.map((s) => (
                    <tr key={s.key}>
                      <th scope="row">{s.label}</th>
                      <td>
                        <SettingValue setting={s} t={t} />
                      </td>
                      <td>
                        {s.variable ? (
                          <code>{s.variable}</code>
                        ) : (
                          <span className="muted">{t('admin.settings.fixed')}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        ))
      )}
    </section>
  );
}
