import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { execSync } from 'node:child_process';

// service.version on browser spans = git sha, matching the backend.
function gitSha(): string {
  try {
    return execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return 'unknown';
  }
}
process.env.VITE_APP_VERSION ??= gitSha();

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
});
