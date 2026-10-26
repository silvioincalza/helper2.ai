import { defineConfig, devices } from '@playwright/test';

// E2E runs against the real stack (backend on :8080, vite on :5173).
// Browsers are NOT installed by this project; run `npx playwright install chromium` yourself.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env['CI'] ? 2 : 0,
  reporter: [['list']],
  use: { baseURL: process.env['H2_E2E_BASE_URL'] ?? 'http://localhost:5173', trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  ...(process.env['H2_E2E_NO_SERVER']
    ? {}
    : { webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true } }),
});
