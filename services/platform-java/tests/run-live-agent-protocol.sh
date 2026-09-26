#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
api_dir="$(cd -- "$module_dir/../agent-api-python" && pwd)"

uv sync --locked --extra test --project "$api_dir"
cd -- "$module_dir"
mvn -f "$module_dir/pom.xml" -Dtest=AgentLiveProtocolIntegrationTest -Dyuan.liveAgentApi=true test
