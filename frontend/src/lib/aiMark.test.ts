import type { AiContentMarker, ValueOrigin } from '@/api/types';
import { AI_ORIGIN, aiMark, aiMarkWhen, aiModel, aiSource, isAiOrigin } from './aiMark';

const marker = (models: string[]): AiContentMarker => ({
  aiGenerated: true,
  provider: 'NVIDIA',
  models,
  fields: [],
  policy: '/mission',
});

describe('AI origins', () => {
  it('names the four origins a model wrote or read', () => {
    expect(Object.values(AI_ORIGIN).sort()).toEqual([
      'AI_ESTIMATED',
      'AI_EXTRACTED',
      'AI_SUMMARY',
      'SELF_DECLARED',
    ]);
  });
  it('knows an AI origin from the others', () => {
    const origins: ValueOrigin[] = ['AI_EXTRACTED', 'AI_SUMMARY', 'AI_ESTIMATED', 'SELF_DECLARED'];
    expect(origins.map(isAiOrigin)).toEqual([true, true, true, true]);
    expect(isAiOrigin('SOURCE_PROVIDED')).toBe(false);
  });
});

describe('aiMark', () => {
  it('marks the text, with the model when it is known', () => {
    expect(aiMark('nemotron')).toEqual({
      'data-ai-generated': 'true',
      'data-ai-model': 'nemotron',
    });
    expect(aiMark()).toEqual({ 'data-ai-generated': 'true' });
    expect(aiMark(null)).toEqual({ 'data-ai-generated': 'true' });
  });
  it('marks only when the condition holds', () => {
    expect(aiMarkWhen(true)).toEqual({ 'data-ai-generated': 'true' });
    expect(aiMarkWhen(true, 'nemotron')).toEqual({
      'data-ai-generated': 'true',
      'data-ai-model': 'nemotron',
    });
    expect(aiMarkWhen(false, 'nemotron')).toEqual({});
  });
  it('names the model alone on an item of a marked list', () => {
    expect(aiModel('nemotron')).toEqual({ 'data-ai-model': 'nemotron' });
    expect(aiModel(undefined)).toEqual({});
  });
});

describe('the source of a block of AI text', () => {
  it('names the provider and the models it knows', () => {
    expect(aiSource(marker(['a', 'b']))).toEqual({
      'data-ai-provider': 'NVIDIA',
      'data-ai-models': 'a b',
    });
    expect(aiSource(null)).toEqual({});
    expect(aiSource(undefined)).toEqual({});
  });
  it('leaves the models attribute out when no model is known', () => {
    const source = aiSource(marker([]));
    expect(source).toEqual({ 'data-ai-provider': 'NVIDIA' });
    expect(source).not.toHaveProperty('data-ai-models');
  });
});
