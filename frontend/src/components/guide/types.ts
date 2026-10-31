import type { GuideResponse } from '@/api/types';
import type { Refinement } from '@/lib/searchState';

export type Recommendation = GuideResponse['recommendations'][number];

/** A follow-up the user asks from the answer: their reply to our question, or parts of the reading to remove. */
export type Refine = (r: Partial<Refinement>) => void;
