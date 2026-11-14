type EvidenceSource = { kind: 'readme' | 'modelCard'; name: string };

const README = /^https:\/\/github\.com\/([A-Za-z0-9-]+\/[A-Za-z0-9._-]+)#readme$/;
const MODEL_CARD =
  /^https:\/\/huggingface\.co\/([A-Za-z0-9._-]+\/[A-Za-z0-9._-]+)\/blob\/[^/]+\/README\.md$/;

export function evidenceSource(url: string): EvidenceSource | null {
  const readme = README.exec(url);
  if (readme?.[1]) return { kind: 'readme', name: readme[1] };
  const card = MODEL_CARD.exec(url);
  if (card?.[1]) return { kind: 'modelCard', name: card[1] };
  return null;
}
