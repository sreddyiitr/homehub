import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config.ts';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['src/test/setup.ts'],
      env: { VITE_API_BASE_URL: 'http://api.test' },
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        // Exclusions are explicit and limited to CLAUDE.md's list (R-TEST-05).
        exclude: ['src/api/schema.gen.ts', 'src/main.tsx', 'src/test/**', 'src/**/*.test.{ts,tsx}'],
        // Gate enforced from Phase 3 (R-TEST-08); configured now so it's visible.
        thresholds: { lines: 90, branches: 90, functions: 90, statements: 90 },
      },
    },
  }),
);
