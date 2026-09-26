#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(cd -- "$module_dir/../.." && pwd)"
if [[ ! -f "$repo_dir/packages/workflow-contracts/node_modules/ajv/package.json" ]]; then
  npm ci --prefix "$repo_dir/packages/workflow-contracts"
fi
mvn -f "$module_dir/pom.xml" -Dtest=GeneratorPreviewServiceTest,WorkflowDraftValidationServiceTest -Dyuan.nodeGenerator=true -Dyuan.nodeStudio=true test
