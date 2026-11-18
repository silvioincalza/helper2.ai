export function TermCommand({ command, total }: { command: string; total: number | null }) {
  return (
    <p className="h2-termlist__cmd">
      <span aria-hidden="true">
        <span className="h2-prompt">$</span> {command}
        {total !== null && ' · '}
      </span>
      {total !== null && <span className="h2-termlist__total">{total}</span>}
    </p>
  );
}
