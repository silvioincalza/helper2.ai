import './adminText';
import type { TFunction } from 'i18next';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { ConfigSetting, ConfigGroup, RuntimeSettingState } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDuration } from '@/lib/duration';
import { formatDate } from '@/lib/format';
import { DiscoveryHostsPanel } from './DiscoveryHostsPanel';
import { KnownTargetsPanel } from './KnownTargetsPanel';
import { RuntimeSettingsPanel } from './RuntimeSettingsPanel';

function settingText(
  setting: ConfigSetting,
  runtime: RuntimeSettingState | undefined,
  t: TFunction,
): string {
  if (runtime) {
    const now =
      runtime.value === null ? t('admin.settings.noCap') : `${runtime.value} ${runtime.unit}`;
    return runtime.overridden ? `${now} (${t('admin.settings.changedAtRuntime')})` : now;
  }
  const { kind, value, unit } = setting;
  switch (kind) {
    case 'FLAG':
      return value === 'true' ? t('admin.settings.on') : t('admin.settings.off');
    case 'CAP':
      if (value === null) return t('admin.settings.noCap');
      return unit ? `${value} ${unit}` : value;
    case 'COUNT':
      return unit ? `${value} ${unit}` : (value ?? '');
    case 'DURATION':
      return value === null ? t('admin.settings.none') : formatDuration(Number(value), t);
    case 'NAMES':
      return value ? value.split(',').join(', ') : t('admin.settings.none');
    default:
      return value ?? t('admin.settings.none');
  }
}

function SettingValue({
  setting,
  runtime,
  t,
}: {
  setting: ConfigSetting;
  runtime: RuntimeSettingState | undefined;
  t: TFunction;
}) {
  const text = settingText(setting, runtime, t);
  const plain = runtime || setting.value === null || setting.kind in PLAIN_KINDS;
  return plain ? <>{text}</> : <code>{text}</code>;
}

const PLAIN_KINDS = { FLAG: true, CAP: true, COUNT: true, DURATION: true, NAMES: true };

function filtered(
  groups: ConfigGroup[],
  words: string,
  shownValue: (setting: ConfigSetting) => string,
): ConfigGroup[] {
  if (!words) return groups;
  const matches = (s: ConfigSetting) =>
    [s.label, s.variable ?? '', shownValue(s), s.key].join(' ').toLowerCase().includes(words);
  return groups
    .map((g) => ({ ...g, settings: g.settings.filter(matches) }))
    .filter((g) => g.settings.length > 0);
}

export default function SettingsPage() {
  const { t } = useTranslation();
  const config = useApi('admin/config', (s) => adminApi.getEffectiveConfig(s), { cache: false });
  const runtime = useApi('admin/runtime-settings', (s) => adminApi.listRuntimeSettings(s), {
    cache: false,
  });
  const { retry, focusRef: heading } = useRetry(config.reload, config.loading);
  const groups = config.data?.groups ?? [];
  const publicApp = config.data?.publicApp ?? null;
  const runtimeSettings = runtime.data?.settings ?? [];
  const runtimeByKey = new Map(runtimeSettings.map((r) => [r.key, r]));
  const [find, setFind] = useState('');
  const words = find.trim().toLowerCase();
  const shown = filtered(groups, words, (s) => settingText(s, runtimeByKey.get(s.key), t));

  return (
    <section aria-labelledby="settings-h" className="h2-admin__part">
      <h2 id="settings-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.settings.title')}
      </h2>
      <p className="muted">{t('admin.settings.lede')}</p>
      <nav aria-label={t('admin.settings.contents')} className="h2-admin__contents">
        <ul>
          <li>
            <a href="#rt-h">{t('admin.settings.runtime.title')}</a>
          </li>
          <li>
            <a href="#dh-h">{t('admin.settings.hosts.title')}</a>
          </li>
          <li>
            <a href="#kt-h">{t('admin.settings.targets.title')}</a>
          </li>
          {groups.map((g) => (
            <li key={g.id}>
              <a href={`#settings-${g.id}`}>{g.title}</a>
            </li>
          ))}
        </ul>
      </nav>
      <RuntimeSettingsPanel
        settings={runtimeSettings}
        loading={runtime.loading}
        error={runtime.error}
        reload={runtime.reload}
      />
      <DiscoveryHostsPanel />
      <KnownTargetsPanel />
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
        <>
          {!publicApp && (
            <p role="status" className="muted">
              {t('admin.settings.publicAppNotStarted')}
            </p>
          )}
          <label className="h2-admin__find">
            {t('admin.settings.find')}
            <input type="search" value={find} onChange={(e) => setFind(e.target.value)} />
          </label>
          {words && shown.length === 0 && (
            <p role="status" className="muted">
              {t('admin.settings.noMatch')}
            </p>
          )}
          {shown.map((g) => (
            <section key={g.id} aria-labelledby={`settings-${g.id}`}>
              <h3 id={`settings-${g.id}`} className="h2-admin__h">
                {g.title}
              </h3>
              {g.app === 'PUBLIC' && publicApp && (
                <p className="muted">
                  {t('admin.settings.fromPublicApp', {
                    build: publicApp.build,
                    at: formatDate(publicApp.publishedAt, 'INSTANT'),
                  })}
                </p>
              )}
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
                          <SettingValue setting={s} runtime={runtimeByKey.get(s.key)} t={t} />
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
          ))}
        </>
      )}
    </section>
  );
}
