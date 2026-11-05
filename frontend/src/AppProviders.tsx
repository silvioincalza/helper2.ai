import type { ReactNode } from 'react';
import { BookmarksProvider } from '@/context/BookmarksContext';
import { MeProvider } from '@/context/MeContext';

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <MeProvider>
      <BookmarksProvider>{children}</BookmarksProvider>
    </MeProvider>
  );
}
