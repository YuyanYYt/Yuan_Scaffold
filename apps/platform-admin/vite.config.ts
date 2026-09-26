import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'APP_');
  return {
    server: {
      host: '127.0.0.1',
      proxy: { '/api': env.APP_API_TARGET || 'http://127.0.0.1:8080' }
    }
  };
});
