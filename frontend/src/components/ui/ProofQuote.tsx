import { locateQuote } from '@/lib/locateQuote';
import './ui.css';

/**
 * The proof for a result: the source's own [text] with the sentence the guide checked ([quote]) highlighted, and the
 * [source] that published it. Shown alone when the text does not hold the quote (it came from the title).
 * The source's words, not AI-written: never marked as AI content (ADR 0038).
 */
export function ProofQuote({ text, quote, source }: { text: string; quote: string; source: string }) {
  const at = locateQuote(text, quote);
  return (
    <figure className="h2-proof">
      <blockquote>
        {at ? <>{at.before}<mark>{at.match}</mark>{at.after}</> : <mark>{quote}</mark>}
      </blockquote>
      <figcaption>{source}</figcaption>
    </figure>
  );
}
