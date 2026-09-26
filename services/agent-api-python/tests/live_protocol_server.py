"""Test-only Uvicorn host for Java-to-Python loopback protocol tests."""

from __future__ import annotations

import argparse
from pathlib import Path

import uvicorn

from yuan_agent_api import BackendResult, ReleaseRecord, create_app
from yuan_agent_api.models import Grant, RunStatus, WorkflowRef


TEST_KEY_ID = "live-test-key"
TEST_KEY = b"test-only-cross-language-hmac-key-32!"
TENANT_ID = "12345678-1234-4234-8234-123456789abc"
PRINCIPAL_ID = "22345678-1234-4234-8234-123456789abc"
WORKFLOW = WorkflowRef(id="asset.faq", version="0.1.0", semantic_sha256="a" * 64)
GRANTS = tuple(
    Grant(group=group, resource_id=resource_id, version="1.0.0")
    for group, resource_id in (
        ("models", "model.asset"),
        ("prompts", "prompt.asset"),
        ("knowledge_bases", "kb.asset"),
        ("indexes", "index.asset"),
    )
)


class TestOnlyCatalog:
    def resolve(self, workflow_ref, context):
        if (workflow_ref != WORKFLOW or context.tenant_id != TENANT_ID
                or context.principal_id != PRINCIPAL_ID):
            return None
        return ReleaseRecord(workflow_ref=WORKFLOW, status="ACTIVE", required_grants=GRANTS)


class TestOnlyExecutor:
    def start(self, workflow_ref, thread_id, user_message, context):
        assert workflow_ref == WORKFLOW and user_message == "live protocol probe"
        assert context.tenant_id == TENANT_ID and context.principal_id == PRINCIPAL_ID
        return BackendResult(RunStatus.PAUSED, None, 3, 0, 0, 3, "USD")

    def resume(self, workflow_ref, thread_id, context):
        assert workflow_ref == WORKFLOW
        assert context.tenant_id == TENANT_ID and context.principal_id == PRINCIPAL_ID
        return BackendResult(
            RunStatus.COMPLETED,
            {"status": "answered", "text": "TEST ONLY live resume", "citation_ids": ["c1"]},
            6, 10, 12, 5, "USD",
        )


class ReadyServer(uvicorn.Server):
    def __init__(self, config: uvicorn.Config, ready_file: Path):
        super().__init__(config)
        self.ready_file = ready_file

    async def startup(self, sockets=None):
        await super().startup(sockets)
        if self.started:
            port = self.servers[0].sockets[0].getsockname()[1]
            staged = self.ready_file.with_suffix(".tmp")
            staged.write_text(str(port), encoding="ascii")
            staged.replace(self.ready_file)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("no-catalog", "no-executor", "test-backend"), required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--ready-file", type=Path, required=True)
    args = parser.parse_args()

    catalog = None if args.mode == "no-catalog" else TestOnlyCatalog()
    executor = TestOnlyExecutor() if args.mode == "test-backend" else None
    app = create_app(
        keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=args.ledger,
        catalog=catalog, executor=executor,
    )
    config = uvicorn.Config(
        app, host="127.0.0.1", port=0, log_level="warning",
        access_log=False, lifespan="off",
    )
    ReadyServer(config, args.ready_file).run()


if __name__ == "__main__":
    main()
