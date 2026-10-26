import { useEffect } from 'react';

export function useTitle(title: string): void {
  useEffect(() => {
    document.title = title ? `${title} · Helper2.ai` : 'Helper2.ai';
  }, [title]);
}
