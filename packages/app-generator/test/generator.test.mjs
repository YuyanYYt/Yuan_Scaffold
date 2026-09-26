import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateManifest } from '../src/manifest.mjs';
import { renderProject } from '../src/render.mjs';
import { generate } from '../src/cli.mjs';
import { previewProject } from '../src/plan.mjs';

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const samplePath = resolve(packageRoot, '../../examples/asset-app/manifest.json');
const sample = JSON.parse(await readFile(samplePath, 'utf8'));
const clone = () => structuredClone(sample);
const artifactRoot = join(packageRoot, '.test-artifacts');

test('manifest accepts the asset app and rejects unsafe names, types and privilege ambiguity', () => {
  assert.deepEqual(validateManifest(sample), []);
  const attempts = [
    manifest => { manifest.project.artifactId = '../escape'; },
    manifest => { manifest.project.packageName = 'dev.yuan.sample.class'; },
    manifest => { manifest.entity.table = 'assets;drop_table'; },
    manifest => { manifest.entity.table = 'app_users'; },
    manifest => { manifest.entity.name = 'String'; },
    manifest => { manifest.entity.name = 'Entity'; },
    manifest => { manifest.entity.apiPath = '/api/csrf'; },
    manifest => { manifest.project.artifactId = 'x'.repeat(300); },
    manifest => { manifest.entity.fields[0].name = 'tenantSlug'; },
    manifest => { manifest.entity.fields[0].type = 'raw_sql'; },
    manifest => { manifest.entity.fields[0].required = false; },
    manifest => { manifest.entity.permissions.write = manifest.entity.permissions.read; },
    manifest => { manifest.entity.fields.push({ ...manifest.entity.fields[0] }); },
    manifest => { manifest.schemaVersion = '2.0.0'; }
  ];
  for (const change of attempts) {
    const manifest = clone();
    change(manifest);
    assert.ok(validateManifest(manifest).length > 0);
  }
});

test('rendering is deterministic, independent of HR, and tenant unique indexes are bounded', () => {
  const files = renderProject(sample);
  assert.deepEqual([...files], [...renderProject(sample)]);
  const sql = files.get('backend/src/main/resources/db/migration/V1__initial.sql');
  assert.match(sql, /ON assets\(tenant_slug, sku\)/);
  assert.ok(sql.match(/CREATE UNIQUE INDEX (\w+)/)[1].length <= 63);
  assert.ok(![...files.values()].some(content => /HR workbench|hr_policy|leave_request/i.test(content)));

  const another = clone();
  another.project.artifactId = 'ticket-app';
  another.project.packageName = 'dev.yuan.sample.ticket';
  another.entity.name = 'Ticket';
  another.entity.table = 'tickets';
  another.entity.apiPath = '/api/tickets';
  another.entity.permissions = { read: 'ticket:read', write: 'ticket:write' };
  another.entity.fields = [{ name: 'subject', type: 'string', required: true, maxLength: 120 }];
  assert.deepEqual(validateManifest(another), []);
  const otherFiles = renderProject(another);
  assert.ok([...otherFiles.keys()].some(name => name.endsWith('/TicketController.java')));
  assert.match(otherFiles.get('backend/src/main/resources/db/migration/V1__initial.sql'), /CREATE TABLE tickets/);
  assert.match(otherFiles.get('web/package-lock.json'), /"name": "ticket-app-web"/);
});

test('preview returns the actual generated source and hashes without writing an application', () => {
  const preview = previewProject(sample);
  assert.equal(preview.previewSchemaVersion, '1.0.0');
  assert.ok(preview.totalBytes > 0 && preview.totalBytes <= 2 * 1024 * 1024);
  assert.ok(preview.files.some(file => file.path.endsWith('/AssetController.java')));
  assert.deepEqual(preview.files.map(file => file.path), [...preview.files.map(file => file.path)].sort());
  for (const file of preview.files) {
    assert.equal(file.bytes, Buffer.byteLength(file.content, 'utf8'));
    assert.equal(file.sha256, createHash('sha256').update(file.content).digest('hex'));
  }
  const record = JSON.parse(preview.files.find(file => file.path === '.yuan-generation.json').content);
  assert.equal(preview.manifestSha256, record.manifestSha256);
  for (const file of preview.files.filter(file => file.path !== '.yuan-generation.json')) {
    assert.equal(record.files[file.path], file.sha256);
  }

  const cli = spawnSync(process.execPath, ['src/cli.mjs', 'preview', '-'], {
    cwd: packageRoot, input: JSON.stringify(sample), encoding: 'utf8', maxBuffer: 3 * 1024 * 1024
  });
  assert.equal(cli.status, 0, cli.stderr);
  assert.deepEqual(JSON.parse(cli.stdout), preview);

  const invalid = spawnSync(process.execPath, ['src/cli.mjs', 'preview', '-'], {
    cwd: packageRoot, input: JSON.stringify({ ...sample, extra: true }), encoding: 'utf8'
  });
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /invalid manifest/);

  const noisy = clone();
  for (let index = 0; index < 600; index++) noisy[`unsupported_${index}`] = index;
  const bounded = spawnSync(process.execPath, ['src/cli.mjs', 'preview', '-'], {
    cwd: packageRoot, input: JSON.stringify(noisy), encoding: 'utf8'
  });
  assert.equal(bounded.status, 1);
  assert.match(bounded.stderr, /^invalid manifest:/);
  assert.match(bounded.stderr, /additional errors omitted/);
  assert.ok(Buffer.byteLength(bounded.stderr) < 16 * 1024);
});

test('generate is reproducible and refuses edited files before writing', async () => {
  await mkdir(artifactRoot, { recursive: true });
  const destination = await mkdtemp(join(artifactRoot, 'generate-'));
  await generate(samplePath, destination);
  await generate(samplePath, destination);
  const record = JSON.parse(await readFile(join(destination, '.yuan-generation.json'), 'utf8'));
  for (const [name, hash] of Object.entries(record.files)) {
    assert.equal(createHash('sha256').update(await readFile(join(destination, name))).digest('hex'), hash);
  }
  const edited = join(destination, 'backend/pom.xml');
  await writeFile(edited, (await readFile(edited, 'utf8')) + '<!-- human edit -->\n');
  await assert.rejects(generate(samplePath, destination), /refusing to overwrite/);
  await assert.rejects(generate(samplePath, destination, { upgrade: true }), /edited generated file/);
  assert.match(await readFile(edited, 'utf8'), /human edit/);
});

test('upgrade accepts intact generated output and records a changed manifest', async () => {
  await mkdir(artifactRoot, { recursive: true });
  const destination = await mkdtemp(join(artifactRoot, 'upgrade-'));
  await generate(samplePath, destination);
  await generate(samplePath, destination, { upgrade: true });
  const before = JSON.parse(await readFile(join(destination, '.yuan-generation.json'), 'utf8'));
  const manifest = clone();
  manifest.entity.permissions.write = 'asset:manage';
  const updatedManifest = join(destination, 'updated-manifest.json');
  await writeFile(updatedManifest, JSON.stringify(manifest));
  await generate(updatedManifest, destination, { upgrade: true });
  const controllerPath = 'backend/src/main/java/dev/yuan/sample/asset/api/AssetController.java';
  const controller = await readFile(join(destination, controllerPath), 'utf8');
  const after = JSON.parse(await readFile(join(destination, '.yuan-generation.json'), 'utf8'));
  assert.match(controller, /asset:manage/);
  assert.notEqual(before.manifestSha256, after.manifestSha256);
  assert.equal(after.files[controllerPath], createHash('sha256').update(controller).digest('hex'));
});
