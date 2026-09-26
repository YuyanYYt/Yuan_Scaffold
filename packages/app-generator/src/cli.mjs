import { readFile, writeFile, mkdir, lstat } from 'node:fs/promises';
import { dirname, join, resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { validateManifest } from './manifest.mjs';
import { renderProject } from './render.mjs';

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

async function generate(manifestPath, outputPath, { upgrade = false } = {}) {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const errors = validateManifest(manifest);
  if (errors.length) throw new Error(`invalid manifest:\n${errors.map(x => `- ${x}`).join('\n')}`);
  const output = resolve(outputPath);
  await ensureNoSymlink(output);
  const files = renderProject(manifest);
  const record = { schemaVersion: '1.0.0', generatorVersion: '0.3.0', manifestSha256: createHash('sha256').update(JSON.stringify(manifest)).digest('hex'), files: {} };
  for (const [name, content] of [...files].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) record.files[name] = createHash('sha256').update(content).digest('hex');
  files.set('.yuan-generation.json', `${JSON.stringify(record, null, 2)}\n`);

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
  if (process.argv.length !== 5 || !['generate', 'upgrade'].includes(process.argv[2])) {
    console.error('usage: node src/cli.mjs <generate|upgrade> <manifest.json> <output-directory>');
    process.exitCode = 2;
  } else {
    generate(process.argv[3], process.argv[4], { upgrade: process.argv[2] === 'upgrade' }).then(result => console.log(`Generated ${result.files} files in ${result.output}`)).catch(error => {
      console.error(error.message);
      process.exitCode = 1;
    });
  }
}

export { generate };
