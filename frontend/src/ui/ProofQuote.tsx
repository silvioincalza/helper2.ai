import { locateQuote } from '@/lib/locateQuote';
import './ui.css';

export function ProofQuote({
  text,
  quote,
  source,
}: {
  text: string;
  quote: string;
  source: string;
}) {
  const at = locateQuote(text, quote);
  return (
    <figure className="h2-proof">
      <blockquote>
        {at ? (
          <>
            {at.before}
            <mark>{at.match}</mark>
            {at.after}
          </>
        ) : (
          <mark>{quote}</mark>
        )}
      </blockquote>
      <figcaption>{source}</figcaption>
    </figure>
  );
}
