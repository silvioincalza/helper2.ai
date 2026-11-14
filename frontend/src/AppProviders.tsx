import type { ReactNode } from 'react';
import { BookmarksProvider } from '@/context/BookmarksContext';
import { MeProvider } from '@/context/MeContext';
import { MonetisationProvider } from '@/context/MonetisationContext';

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <MonetisationProvider>
      <MeProvider>
        <BookmarksProvider>{children}</BookmarksProvider>
      </MeProvider>
    </MonetisationProvider>
  );
}
