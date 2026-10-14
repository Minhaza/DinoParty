import { defineConfig, devices } from '@playwright/test'

/**
 * Minimal E2E smoke suite (P5-05). Needs the backend on :8080 with its database
 * (`scripts/dev.sh` or `docker compose … up -d db` + `mvnw spring-boot:run`); the Vite dev server
 * is started here unless one is already running. Multi-user scenarios open one browser context
 * per player inside a test, so the suite itself runs serially.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev',
        url: 'http://localhost:5173',
        reuseExistingServer: true,
        timeout: 60_000,
      },
})
