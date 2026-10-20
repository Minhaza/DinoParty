import { defineConfig, devices } from '@playwright/test'

/**
 * E2E suite (P5-05, run in CI since P7-03). Needs the backend on :8080 with its database
 * (`scripts/dev.sh` or `docker compose … up -d db` + `mvnw spring-boot:run`; CI uses a PostgreSQL
 * service and the packaged jar); the Vite dev server is started here (locally it reuses a running one). Multi-user scenarios open one browser context
 * per player inside a test, so the suite itself runs serially.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  forbidOnly: !!process.env.CI,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [['list'], ['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
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
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})
