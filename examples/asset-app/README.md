# asset-app

This independent Spring Boot + React application was generated from a Yuan Scaffold manifest. It contains one tenant scoped entity (Asset) and no platform runtime dependency.

## Run

1. Use Java 21 and Maven 3.9; run `mvn test` in `backend/`.
2. Set `APP_BOOTSTRAP_TENANT`, `APP_BOOTSTRAP_USERNAME`, and a 12+ character `APP_BOOTSTRAP_PASSWORD` in the shell. No account or password is preloaded.
3. Run `mvn spring-boot:run` in `backend/`. The app listens on port 8082 by default. Use HTTP Basic login `tenant/username` with the configured password. The first account receives `asset:read` and `asset:write`. Bootstrap credentials initialize only an empty user table; changing them after initialization fails startup rather than creating a new privileged user.
4. Use Node 20.19+/22.12+; run `npm ci` and `npm run dev` in `web/`. Vite proxies `/api` to the backend. Set `APP_API_TARGET` for a nondefault backend URL.

The browser page keeps credentials only in memory. It fetches a CSRF token before each mutation. HTTP Basic is a local demonstration identity scheme; deploy behind HTTPS and replace it with your production identity integration before exposing the app. H2 file storage is the local sample database. API list/get/update/delete operations always scope by the authenticated principal's tenant; no tenant value is accepted from a client header or body. Only H2 is currently tested, and migration to another database requires reviewed DDL.

Generated files are recorded in `.yuan-generation.json`. Re-running the generator accepts byte-identical files and refuses to overwrite edited files. The manifest is the source of generation settings; migration changes for existing data require a reviewed migration rather than silently replaying V1.
