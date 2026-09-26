const javaWords = new Set(`abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while record sealed permits non-sealed var yield`.split(' '));
const sqlWords = new Set(`all and as by check constraint create date default delete desc distinct drop false from group having in index insert into is join key like limit not null on or order primary references select set table true union unique update user values varchar where`.split(' '));
const fieldTypes = new Set(['string', 'integer', 'boolean']);

function ownKeys(value, allowed, where, errors) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    errors.push(`${where} must be an object`);
    return false;
  }
  for (const key of Object.keys(value)) if (!allowed.includes(key)) errors.push(`${where}.${key} is not supported`);
  return true;
}

function matches(value, pattern, where, errors) {
  if (typeof value !== 'string' || !pattern.test(value)) errors.push(`${where} has an unsafe or missing name`);
}

export function validateManifest(manifest) {
  const errors = [];
  if (!ownKeys(manifest, ['schemaVersion', 'generatorVersion', 'project', 'entity'], 'manifest', errors)) return errors;
  if (manifest.schemaVersion !== '1.0.0') errors.push('schemaVersion must be 1.0.0');
  if (manifest.generatorVersion !== '0.3.0') errors.push('generatorVersion must be 0.3.0');

  const project = manifest.project;
  if (ownKeys(project, ['groupId', 'artifactId', 'packageName'], 'project', errors)) {
    matches(project.groupId, /^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){1,8}$/, 'project.groupId', errors);
    matches(project.packageName, /^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){2,10}$/, 'project.packageName', errors);
    matches(project.artifactId, /^[a-z][a-z0-9]*(?:-[a-z0-9]+){0,8}$/, 'project.artifactId', errors);
    if (typeof project.groupId === 'string' && project.groupId.length > 100) errors.push('project.groupId is too long');
    if (typeof project.packageName === 'string' && project.packageName.length > 120) errors.push('project.packageName is too long');
    if (typeof project.artifactId === 'string' && project.artifactId.length > 60) errors.push('project.artifactId is too long');
    if (typeof project.packageName === 'string' && typeof project.groupId === 'string' &&
        !project.packageName.startsWith(`${project.groupId}.`)) errors.push('project.packageName must be nested under project.groupId');
    for (const segment of `${project.packageName ?? ''}`.split('.')) if (javaWords.has(segment)) errors.push(`Java package segment ${segment} is reserved`);
  }

  const entity = manifest.entity;
  if (ownKeys(entity, ['name', 'table', 'apiPath', 'fields', 'permissions'], 'entity', errors)) {
    matches(entity.name, /^[A-Z][A-Za-z0-9]{1,63}$/, 'entity.name', errors);
    matches(entity.table, /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/, 'entity.table', errors);
    matches(entity.apiPath, /^\/api\/[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/, 'entity.apiPath', errors);
    if (typeof entity.apiPath === 'string' && (entity.apiPath.length > 80 || entity.apiPath === '/api/csrf')) errors.push('entity.apiPath is reserved or too long');
    if (typeof entity.table === 'string' && (entity.table.length > 50 || sqlWords.has(entity.table))) errors.push('entity.table is reserved or too long');
    if (['app_users', 'flyway_schema_history'].includes(entity.table)) errors.push('entity.table conflicts with generated infrastructure');
    if (typeof entity.name === 'string' && javaWords.has(entity.name.toLowerCase())) errors.push('entity.name is reserved');
    if (!Array.isArray(entity.fields) || entity.fields.length < 1 || entity.fields.length > 32) errors.push('entity.fields must have 1-32 entries');
    else {
      const names = new Set();
      const columns = new Set();
      for (const [index, field] of entity.fields.entries()) {
        const where = `entity.fields[${index}]`;
        if (!ownKeys(field, ['name', 'type', 'required', 'maxLength', 'precision', 'scale', 'minimum', 'unique'], where, errors)) continue;
        matches(field.name, /^[a-z][A-Za-z0-9]{0,63}$/, `${where}.name`, errors);
        if (typeof field.name !== 'string') continue;
        const column = field.name.replace(/[A-Z]/g, match => `_${match.toLowerCase()}`);
        if (field.name === 'id' || field.name === 'tenantSlug' || field.name === 'createdAt' || field.name === 'updatedAt' || javaWords.has(field.name) || sqlWords.has(column)) errors.push(`${where}.name is reserved`);
        if (column.length > 50) errors.push(`${where}.name exceeds SQL identifier length`);
        if (names.has(field.name) || columns.has(column)) errors.push(`${where}.name is duplicated`);
        names.add(field.name);
        columns.add(column);
        if (!fieldTypes.has(field.type)) errors.push(`${where}.type is unsupported`);
        if (typeof field.required !== 'boolean') errors.push(`${where}.required must be boolean`);
        if (field.unique !== undefined && typeof field.unique !== 'boolean') errors.push(`${where}.unique must be boolean`);
        if (field.type === 'string') {
          if (!Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > 1024) errors.push(`${where}.maxLength must be 1-1024`);
          if (field.precision !== undefined || field.scale !== undefined || field.minimum !== undefined) errors.push(`${where} has type-incompatible options`);
        } else if (field.maxLength !== undefined) errors.push(`${where}.maxLength applies only to strings`);
        if (field.precision !== undefined || field.scale !== undefined) errors.push(`${where}.precision/scale are not supported in v0.3`);
        if (field.minimum !== undefined && (field.type !== 'integer' || !Number.isSafeInteger(field.minimum) || field.minimum < 0 || field.minimum > 2147483647)) errors.push(`${where}.minimum must be a nonnegative integer bound`);
        if (field.unique && !field.required) errors.push(`${where}.unique must also be required in this version`);
        if (field.unique && field.type !== 'string') errors.push(`${where}.unique is supported only for strings`);
      }
    }
    const permissions = entity.permissions;
    if (ownKeys(permissions, ['read', 'write'], 'entity.permissions', errors)) {
      for (const action of ['read', 'write']) matches(permissions[action], /^[a-z][a-z0-9]{0,31}:[a-z][a-z0-9]{0,31}$/, `entity.permissions.${action}`, errors);
      if (permissions.read === permissions.write) errors.push('read and write permissions must differ');
    }
  }
  return errors;
}
