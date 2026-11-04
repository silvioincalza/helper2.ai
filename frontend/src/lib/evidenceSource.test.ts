import { evidenceSource } from './evidenceSource';

describe('evidenceSource', () => {
  it('names the repository of a README', () => {
    expect(evidenceSource('https://github.com/acme/tool/blob/HEAD/README.md')).toEqual({ kind: 'readme', name: 'acme/tool' });
  });

  it('names the model of a model card', () => {
    expect(evidenceSource('https://huggingface.co/meta-llama/Llama-4/blob/main/README.md')).toEqual({
      kind: 'modelCard',
      name: 'meta-llama/Llama-4',
    });
  });

  it('says nothing about other pages', () => {
    expect(evidenceSource('https://github.com/acme/tool')).toBeNull();
    expect(evidenceSource('https://github.com.evil.example/acme/tool/blob/HEAD/README.md')).toBeNull();
  });
});
