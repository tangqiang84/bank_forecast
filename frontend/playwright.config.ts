import { defineConfig, devices } from '@playwright/test'

const isCI = process.env.CI === '1' || process.env.CI === 'true'

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: isCI ? 'chromium-ci' : 'chrome-local',
      use: {
        ...devices['Desktop Chrome'],
        ...(isCI ? {} : { channel: 'chrome' }),
      },
    },
  ],
  webServer: {
    command: 'pnpm exec vite --host 127.0.0.1 --port 5173 --strictPort',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !isCI,
    timeout: 60_000,
  },
})
