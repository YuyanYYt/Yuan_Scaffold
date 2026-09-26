#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mvn -f "$module_dir/pom.xml" -Dtest=GeneratorPreviewServiceTest -Dyuan.nodeGenerator=true test
