import { readFile, writeFile, mkdir, lstat, stat } from 'node:fs/promises';
import { dirname, join, resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { buildGenerationPlan, previewProject } from './plan.mjs';

async function statOrNull(path) {
  try { return await lstat(path); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}

async function ensureNoSymlink(path) {
  let current = resolve(path);
  const paths = [];
  while (true) {
    paths.push(current);
    const parent = dirname(current);
    if (parent === current) break;
    current = parent;
  }
  for (const part of paths) if ((await statOrNull(part))?.isSymbolicLink()) throw new Error(`symbolic link in output path: ${part}`);
}

async function readPreviewManifest(path) {
  const maxBytes = 256 * 1024;
  if (path !== '-') {
    if ((await stat(path)).size > maxBytes) throw new Error('preview manifest exceeds 256 KiB');
    return JSON.parse(await readFile(path, 'utf8'));
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length;
    if (bytes > maxBytes) throw new Error('preview manifest exceeds 256 KiB');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function generate(manifestPath, outputPath, { upgrade = false } = {}) {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const output = resolve(outputPath);
  await ensureNoSymlink(output);
  const { files } = buildGenerationPlan(manifest);

  let previousFiles = {};
  if (upgrade) {
    const oldRecordPath = join(output, '.yuan-generation.json');
    if (!(await statOrNull(oldRecordPath))) throw new Error('cannot upgrade without .yuan-generation.json');
    const oldRecord = JSON.parse(await readFile(oldRecordPath, 'utf8'));
    if (!oldRecord.files || typeof oldRecord.files !== 'object') throw new Error('invalid previous generation record');
    previousFiles = oldRecord.files;
    for (const [name, oldHash] of Object.entries(previousFiles)) {
      if (!files.has(name)) throw new Error(`upgrade would leave obsolete generated file: ${name}`);
      const target = join(output, name);
      await ensureNoSymlink(target);
      const stat = await statOrNull(target);
      if (!stat?.isFile() || createHash('sha256').update(await readFile(target)).digest('hex') !== oldHash)
        throw new Error(`refusing to overwrite missing or edited generated file: ${target}`);
    }
  }

  // Check the entire destination first. No file is written if an earlier generation
  // or a human edit conflicts with the proposed output.
  for (const [name, content] of files) {
    if (name.startsWith('/') || name.split('/').includes('..') || relative(output, join(output, name)).startsWith(`..${sep}`)) throw new Error(`unsafe generated path: ${name}`);
    const target = join(output, name);
    await ensureNoSymlink(target);
    const stat = await statOrNull(target);
    if (stat && !stat.isFile()) throw new Error(`destination is not a file: ${target}`);
    if (stat && await readFile(target, 'utf8') !== content && !upgrade) throw new Error(`refusing to overwrite existing or edited file: ${target}`);
    if (stat && await readFile(target, 'utf8') !== content && upgrade && name !== '.yuan-generation.json' && !(name in previousFiles))
      throw new Error(`refusing to overwrite unmanaged file: ${target}`);
  }
  for (const [name, content] of files) {
    const target = join(output, name);
    if (await statOrNull(target)) {
      if (upgrade && await readFile(target, 'utf8') !== content) await writeFile(target, content);
      continue;
    }
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content, { flag: 'wx' });
  }
  return { output, files: files.size };
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const command = process.argv[2];
  if (command === 'preview' && process.argv.length === 4) {
    readPreviewManifest(process.argv[3])
      .then(previewProject)
      .then(result => console.log(JSON.stringify(result)))
      .catch(error => { console.error(error.message); process.exitCode = 1; });
  } else if (['generate', 'upgrade'].includes(command) && process.argv.length === 5) {
    generate(process.argv[3], process.argv[4], { upgrade: command === 'upgrade' })
      .then(result => console.log(`Generated ${result.files} files in ${result.output}`))
      .catch(error => { console.error(error.message); process.exitCode = 1; });
  } else {
    console.error('usage: node src/cli.mjs preview <manifest.json|-> | <generate|upgrade> <manifest.json> <output-directory>');
    process.exitCode = 2;
  }
}

export { generate };
