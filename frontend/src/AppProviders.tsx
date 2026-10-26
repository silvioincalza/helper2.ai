import type { ReactNode } from 'react';
import { BookmarksProvider } from '@/context/BookmarksContext';
import { MeProvider } from '@/context/MeContext';
import { SourcesProvider } from '@/context/SourcesContext';

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <MeProvider>
      <BookmarksProvider>
        <SourcesProvider>{children}</SourcesProvider>
      </BookmarksProvider>
    </MeProvider>
  );
}
