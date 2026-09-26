import { validateManifest } from '@yuan-scaffold/app-generator/src/manifest.mjs';
import type { EditorValue, Manifest, ManifestField } from './types';

export function blankEditor(): EditorValue {
  return {
    name: '',
    manifest: {
      schemaVersion: '1.0.0',
      generatorVersion: '0.3.0',
      project: { groupId: 'dev.yuan.app', artifactId: '', packageName: 'dev.yuan.app.' },
      entity: {
        name: '', table: '', apiPath: '/api/',
        fields: [{ name: '', type: 'string', required: true, maxLength: 120 }],
        permissions: { read: '', write: '' }
      }
    }
  };
}

export function blankField(): ManifestField {
  return { name: '', type: 'string', required: false, maxLength: 120 };
}

export function isEditableManifest(value: unknown): value is Manifest {
  return validateManifest(value).length === 0;
}

export function validateEditor(value: EditorValue): string[] {
  const errors = validateManifest(value.manifest);
  if (!value.name.trim()) errors.unshift('项目名称不能为空');
  if (value.name.trim().length > 120) errors.unshift('项目名称不能超过 120 个字符');
  if (new TextEncoder().encode(JSON.stringify(value.manifest)).length > 65_536) {
    errors.push('Manifest 超过服务端 64 KiB 上限');
  }
  return errors;
}

export function cloneEditor(value: EditorValue): EditorValue {
  return structuredClone(value);
}
