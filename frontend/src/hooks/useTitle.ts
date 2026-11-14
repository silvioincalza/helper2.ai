import { useEffect } from 'react';

export function useTitle(title: string): void {
  useEffect(() => {
    document.title = title ? `${title} · helper2.ai` : 'helper2.ai';
  }, [title]);
}
