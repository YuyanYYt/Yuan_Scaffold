import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'APP_');
  return { server: { proxy: { '/api': env.APP_API_TARGET || 'http://localhost:8082' } } };
});
