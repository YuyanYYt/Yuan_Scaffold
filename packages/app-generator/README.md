# v0.3 independent application generator

This is a bounded metadata-to-source slice of Yuan Scaffold. It reads a versioned manifest, validates names, field types and permissions, and emits an independent Spring Boot 4.1.1 / Java 21 application plus a React page and typed API client. The implementation and templates are original to this repository; no RuoYi source is used.

## Input and commands

The input is JSON with `schemaVersion: "1.0.0"`, `generatorVersion: "0.3.0"`, `project.{groupId,artifactId,packageName}` and one `entity` containing `name`, `table`, `apiPath`, `fields[]`, and distinct `permissions.read` / `permissions.write`. See [the asset manifest](../../examples/asset-app/manifest.json). In this version, supported field types are `string` (with `maxLength`), `integer` (optional nonnegative `minimum`) and `boolean`. `required` is explicit. `unique` is supported for required strings and creates a `(tenant_slug, field)` unique index, so equal business keys in different tenants do not collide.

```bash
node src/cli.mjs generate ../../examples/asset-app/manifest.json ../../examples/asset-app
node src/cli.mjs upgrade ../../examples/asset-app/manifest.json ../../examples/asset-app
node src/cli.mjs preview ../../examples/asset-app/manifest.json
npm test
```

`generate` creates absent files and accepts byte-identical reruns. It refuses to overwrite any changed or unmanaged file. `upgrade` requires `.yuan-generation.json`, checks SHA-256 of every previously managed file before writing, and refuses changed files. Generation records include the exact manifest hash, generator version and generated file hashes; they are review records, not a cryptographic signature. A reviewed migration is required when an existing database schema changes. Neither command deletes files.

`preview <manifest.json|->` produces the same generated files in a bounded JSON plan without writing an application. Each file contains its path, UTF-8 content, byte count and SHA-256; the plan includes the manifest hash and total source bytes. Standard input is capped at 256 KiB and the generated source at 2 MiB. Invalid Manifest errors are bounded so the platform can reliably return a 400 response. Entity names that would shadow Java template types or imports, including `String` and `Entity`, are rejected before preview or export.

## Generated project

- `backend/`: Spring MVC CRUD API, JPA entity/repository/service, Flyway V1, H2 local database, Spring Security method permissions, health endpoint and integration tests.
- `web/`: React page and typed API client. The demo login is held in page memory; it is not stored in localStorage, a URL or build configuration. Vite proxies `/api` to `APP_API_TARGET` (default port 8082).
- `.yuan-generation.json`: input and output hashes for reproducibility and edit protection.

The entity service takes tenant scope only from the authenticated `TenantPrincipal`. The generated sample uses database-backed HTTP Basic accounts with the login form `tenant/username`. No account is preloaded. Shell environment bootstrap values create one first account only when the user table is empty; changed bootstrap identity or password after initialization fails startup. The browser obtains a CSRF token for mutations, and server-side CSRF remains enabled. HTTP Basic and H2 are local demonstration choices; deployment needs HTTPS and a production identity/data strategy. The sample has no dependency on a live Yuan Scaffold platform.

## Current boundaries

This slice generates one CRUD entity and one React page. It does not yet generate the Java control plane's role/menu administration, Python Agent export, workflow adapter, database dialect variants, visual editor or conflict-aware semantic merge. These remain in the canonical [development map](../../ProjectDocs/项目总地图.md). Existing generated Java can be hand-edited; `upgrade` then refuses to overwrite it until the change is reviewed. Only H2's V1 migration has been built and tested. The Bootstrap account is a local first-run convenience, not a user-management API.

The platform's v0.4 ZIP endpoint currently packages this generator's preview as an explicitly **unverified source draft**. It does not compile and test each submitted Manifest before download. The tracked Asset sample and one exported Asset ZIP were independently built and tested; that evidence does not certify every possible Manifest. The project map requires isolated formatting, Java compilation/tests and Web type checks before a future ZIP can be marked as a verified application export.

The Spring Boot version and starter names follow the [official Spring Boot 4.1 documentation](https://docs.spring.io/spring-boot/reference/using/build-systems.html) and [system requirements](https://docs.spring.io/spring-boot/system-requirements.html).
