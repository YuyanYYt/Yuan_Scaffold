import { createHash } from 'node:crypto';
import { validateManifest } from './manifest.mjs';
import { renderProject } from './render.mjs';

const MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
const byPath = ([a], [b]) => a < b ? -1 : a > b ? 1 : 0;

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

export function buildGenerationPlan(manifest) {
  const errors = validateManifest(manifest);
  if (errors.length) {
    const shown = errors.slice(0, 20).map(error => `- ${String(error).slice(0, 200)}`);
    if (errors.length > shown.length) shown.push(`- ${errors.length - shown.length} additional errors omitted`);
    throw new Error(`invalid manifest:\n${shown.join('\n')}`);
  }

  const files = renderProject(manifest);
  const record = {
    schemaVersion: '1.0.0',
    generatorVersion: '0.3.0',
    manifestSha256: sha256(JSON.stringify(manifest)),
    files: {}
  };
  for (const [name, content] of [...files].sort(byPath)) {
    record.files[name] = sha256(content);
  }
  files.set('.yuan-generation.json', `${JSON.stringify(record, null, 2)}\n`);
  return { files, record };
}

export function previewProject(manifest) {
  const { files, record } = buildGenerationPlan(manifest);
  const result = [...files]
    .sort(byPath)
    .map(([path, content]) => ({
      path,
      sha256: sha256(content),
      bytes: Buffer.byteLength(content, 'utf8'),
      content
    }));
  const totalBytes = result.reduce((total, file) => total + file.bytes, 0);
  if (totalBytes > MAX_PREVIEW_BYTES) throw new Error('generated preview exceeds 2 MiB');
  return {
    previewSchemaVersion: '1.0.0',
    generatorVersion: record.generatorVersion,
    manifestSha256: record.manifestSha256,
    totalBytes,
    files: result
  };
}
