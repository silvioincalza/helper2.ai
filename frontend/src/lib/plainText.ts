export function plainText(text: string): string {
  return text.replace(/`+/g, '').replace(/\s+/g, ' ').trim();
}
