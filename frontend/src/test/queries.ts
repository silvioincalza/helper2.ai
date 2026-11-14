import { screen } from '@testing-library/react';

export function getCommandLine(text: string): HTMLElement {
  return screen.getByText((_, el) => el?.tagName === 'P' && el.textContent === text);
}
