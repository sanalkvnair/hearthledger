import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const fromGit = () => {
  try { return execSync('git rev-parse HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); } catch { return ''; }
};
const fromFile = () => {
  try { return readFileSync('../VERSION', 'utf8').trim(); } catch { return 'dev'; }
};
const version = process.env.APP_VERSION || fromFile();
const commit = (process.env.GIT_SHA || fromGit()).slice(0, 7);

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(version), __APP_COMMIT__: JSON.stringify(commit) },
  server: {
    port: 5173,
    proxy: { '/api': backend, '/oauth2': backend, '/login/oauth2': backend },
  },
});
