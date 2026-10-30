import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceType } from '@/api/types';
import './ui.css';

type Tone = 'paper' | 'model' | 'news' | 'mcp' | 'other';

/** Design system v3, Type tags: fill and text differ in lightness, not only in hue. */
const TONE: Partial<Record<ResourceType, Tone>> = {
  PAPER: 'paper', LIBRARY: 'paper', MODEL: 'model', NEWS: 'news', ARTICLE: 'news', MCP_SERVER: 'mcp',
};

/** The one type tag every resource carries: the type's name ([children] when the source states a finer kind), in mono capitals. */
export function TypeTag({ type, children }: { type: ResourceType; children?: ReactNode }) {
  const { t } = useTranslation();
  return <span className={`h2-type-tag h2-type-tag--${TONE[type] ?? 'other'}`}>{children ?? t(`type.${type}`)}</span>;
}
