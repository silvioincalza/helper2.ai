import { useTranslation } from 'react-i18next';
import type { ResourceType } from '@/api/types';
import './ui.css';

type Tone = 'paper' | 'model' | 'news' | 'mcp' | 'other';

/** Design system v3, Type tags: fill and text differ in lightness, not only in hue. */
const TONE: Partial<Record<ResourceType, Tone>> = {
  PAPER: 'paper', LIBRARY: 'paper', MODEL: 'model', NEWS: 'news', ARTICLE: 'news', MCP_SERVER: 'mcp',
};

/** The one type tag every resource carries: the type's name, in mono capitals. */
export function TypeTag({ type }: { type: ResourceType }) {
  const { t } = useTranslation();
  return <span className={`h2-type-tag h2-type-tag--${TONE[type] ?? 'other'}`}>{t(`type.${type}`)}</span>;
}
