import { render, screen } from '@testing-library/react';
import { OpenButton } from './OpenButton';

describe('OpenButton', () => {
  it.each([
    'data:text/html,<script>alert(1)</script>',
    'javascript:alert(1)',
    'vbscript:msgbox(1)',
    'not a url',
  ])('shows no button for %s', (url) => {
    render(<OpenButton url={url} host="evil.example" />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('opens a web link in a new tab', () => {
    render(<OpenButton url="https://github.com/acme/translator" host="github.com" />);
    const open = screen.getByRole('link', { name: /Open on github.com/ });
    expect(open).toHaveAttribute('href', 'https://github.com/acme/translator');
    expect(open).toHaveAttribute('target', '_blank');
    expect(open).toHaveAttribute('rel', 'noopener noreferrer nofollow ugc');
  });
});
