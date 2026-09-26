"""SQLite nonce and run ledger for a single-process local deployment profile."""

from __future__ import annotations

import os
import sqlite3
import stat
import time
from contextlib import closing, contextmanager
from dataclasses import dataclass
from pathlib import Path

from .errors import CONFLICT, NOT_FOUND
from .models import RunResponse, RunStatus, WorkflowRef


@dataclass(frozen=True)
class BeginDecision:
    new: bool
    prior_response: RunResponse | None


@dataclass(frozen=True)
class RunRecord:
    run_id: str
    tenant_id: str
    principal_id: str
    thread_id: str
    workflow_ref: WorkflowRef
    status: RunStatus
    response: RunResponse | None


class SqliteLedger:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        if not self.path.parent.is_dir():
            raise ValueError("ledger parent directory does not exist")
        if stat.S_IMODE(self.path.parent.stat().st_mode) & 0o077:
            raise ValueError("ledger parent directory must be private (0700 or stricter)")
        if self.path.is_symlink():
            raise ValueError("ledger must not be a symlink")
        if not self.path.exists():
            flags = os.O_CREAT | os.O_EXCL | os.O_RDWR
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            try:
                handle = os.open(self.path, flags, 0o600)
            except FileExistsError:
                pass
            else:
                os.close(handle)
        if stat.S_IMODE(self.path.stat().st_mode) & 0o077:
            raise ValueError("ledger file must not be accessible by group or others")
        with self._connection() as connection:
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS nonces (
                    key_id TEXT NOT NULL,
                    jti TEXT NOT NULL PRIMARY KEY,
                    expires_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS nonces_global_jti
                    ON nonces(jti);
                CREATE TABLE IF NOT EXISTS runs (
                    run_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    principal_id TEXT NOT NULL,
                    thread_id TEXT NOT NULL,
                    workflow_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL,
                    workflow_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    response_json TEXT,
                    updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS runs_thread_owner
                    ON runs(tenant_id, principal_id, thread_id);
                CREATE TABLE IF NOT EXISTS operations (
                    tenant_id TEXT NOT NULL,
                    principal_id TEXT NOT NULL,
                    request_id TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    body_sha256 TEXT NOT NULL,
                    response_json TEXT,
                    PRIMARY KEY (tenant_id, principal_id, request_id)
                );
            """)

    @contextmanager
    def _connection(self):
        with closing(sqlite3.connect(str(self.path), timeout=5)) as connection:
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA busy_timeout=5000")
            connection.execute("PRAGMA journal_mode=WAL")
            yield connection
            connection.commit()

    @staticmethod
    def _record(row: sqlite3.Row) -> RunRecord:
        return RunRecord(
            run_id=row["run_id"], tenant_id=row["tenant_id"], principal_id=row["principal_id"],
            thread_id=row["thread_id"],
            workflow_ref=WorkflowRef(id=row["workflow_id"], version=row["workflow_version"],
                                     semantic_sha256=row["workflow_hash"]),
            status=RunStatus(row["status"]),
            response=RunResponse.model_validate_json(row["response_json"]) if row["response_json"] else None,
        )

    @staticmethod
    def _assert_owner(record: RunRecord, *, tenant_id: str, principal_id: str,
                      thread_id: str, workflow_ref: WorkflowRef) -> None:
        if (record.tenant_id != tenant_id or record.principal_id != principal_id
                or record.thread_id != thread_id or record.workflow_ref != workflow_ref):
            raise NOT_FOUND

    def claim_nonce(self, key_id: str, jti: str, expires_at: int) -> bool:
        with self._connection() as connection:
            try:
                connection.execute(
                    "INSERT INTO nonces(key_id, jti, expires_at) VALUES (?, ?, ?)",
                    (key_id, jti, expires_at),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def get_run(self, *, run_id: str, tenant_id: str, principal_id: str,
                thread_id: str, workflow_ref: WorkflowRef) -> RunRecord:
        with self._connection() as connection:
            row = connection.execute("SELECT * FROM runs WHERE run_id=?", (run_id,)).fetchone()
        if row is None:
            raise NOT_FOUND
        record = self._record(row)
        self._assert_owner(record, tenant_id=tenant_id, principal_id=principal_id,
                           thread_id=thread_id, workflow_ref=workflow_ref)
        return record

    def _prior_operation(self, connection: sqlite3.Connection, *, tenant_id: str,
                         principal_id: str, request_id: str, run_id: str,
                         kind: str, body_sha256: str) -> BeginDecision | None:
        row = connection.execute(
            "SELECT * FROM operations WHERE tenant_id=? AND principal_id=? AND request_id=?",
            (tenant_id, principal_id, request_id),
        ).fetchone()
        if row is None:
            return None
        if row["run_id"] != run_id or row["kind"] != kind or row["body_sha256"] != body_sha256:
            raise CONFLICT
        response = RunResponse.model_validate_json(row["response_json"]) if row["response_json"] else None
        return BeginDecision(False, response)

    def begin_start(self, *, run_id: str, tenant_id: str, principal_id: str,
                    thread_id: str, workflow_ref: WorkflowRef,
                    request_id: str, body_sha256: str) -> BeginDecision:
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            prior = self._prior_operation(connection, tenant_id=tenant_id,
                                          principal_id=principal_id, request_id=request_id,
                                          run_id=run_id, kind="start", body_sha256=body_sha256)
            if prior is not None:
                existing = connection.execute("SELECT * FROM runs WHERE run_id=?", (run_id,)).fetchone()
                if existing is None:
                    raise CONFLICT
                self._assert_owner(self._record(existing), tenant_id=tenant_id, principal_id=principal_id,
                                   thread_id=thread_id, workflow_ref=workflow_ref)
                return prior
            existing = connection.execute("SELECT * FROM runs WHERE run_id=?", (run_id,)).fetchone()
            if existing is not None:
                record = self._record(existing)
                self._assert_owner(record, tenant_id=tenant_id, principal_id=principal_id,
                                   thread_id=thread_id, workflow_ref=workflow_ref)
                raise CONFLICT
            existing_thread = connection.execute(
                "SELECT run_id FROM runs WHERE tenant_id=? AND principal_id=? AND thread_id=?",
                (tenant_id, principal_id, thread_id),
            ).fetchone()
            if existing_thread is not None:
                raise CONFLICT
            connection.execute(
                "INSERT INTO runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                (run_id, tenant_id, principal_id, thread_id, workflow_ref.id,
                 workflow_ref.version, workflow_ref.semantic_sha256, RunStatus.IN_PROGRESS.value,
                 int(time.time())),
            )
            connection.execute(
                "INSERT INTO operations VALUES (?, ?, ?, ?, 'start', ?, NULL)",
                (tenant_id, principal_id, request_id, run_id, body_sha256),
            )
        return BeginDecision(True, None)

    def begin_resume(self, *, run_id: str, tenant_id: str, principal_id: str,
                     thread_id: str, workflow_ref: WorkflowRef,
                     request_id: str, body_sha256: str) -> BeginDecision:
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM runs WHERE run_id=?", (run_id,)).fetchone()
            if row is None:
                raise NOT_FOUND
            record = self._record(row)
            self._assert_owner(record, tenant_id=tenant_id, principal_id=principal_id,
                               thread_id=thread_id, workflow_ref=workflow_ref)
            prior = self._prior_operation(connection, tenant_id=tenant_id,
                                          principal_id=principal_id, request_id=request_id,
                                          run_id=run_id, kind="resume", body_sha256=body_sha256)
            if prior is not None:
                return prior
            if record.status != RunStatus.PAUSED:
                raise CONFLICT
            connection.execute("UPDATE runs SET status=?, updated_at=? WHERE run_id=?",
                               (RunStatus.IN_PROGRESS.value, int(time.time()), run_id))
            connection.execute(
                "INSERT INTO operations VALUES (?, ?, ?, ?, 'resume', ?, NULL)",
                (tenant_id, principal_id, request_id, run_id, body_sha256),
            )
        return BeginDecision(True, None)

    def finish(self, *, run_id: str, tenant_id: str, principal_id: str,
               request_id: str, response: RunResponse) -> None:
        body = response.model_dump_json()
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            run = connection.execute("SELECT tenant_id,principal_id,status FROM runs WHERE run_id=?", (run_id,)).fetchone()
            if run is None or run["tenant_id"] != tenant_id or run["principal_id"] != principal_id or run["status"] != RunStatus.IN_PROGRESS.value:
                raise CONFLICT
            changed = connection.execute(
                "UPDATE operations SET response_json=? WHERE tenant_id=? AND principal_id=? AND request_id=? AND run_id=? AND response_json IS NULL",
                (body, tenant_id, principal_id, request_id, run_id),
            ).rowcount
            if changed != 1:
                raise CONFLICT
            connection.execute(
                "UPDATE runs SET status=?, response_json=?, updated_at=? WHERE run_id=?",
                (response.status.value, body, int(time.time()), run_id),
            )
