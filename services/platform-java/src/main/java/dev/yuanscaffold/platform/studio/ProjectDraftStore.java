package dev.yuanscaffold.platform.studio;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectDraftStore {
    private final JdbcTemplate jdbc;

    public ProjectDraftStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tenantId, UUID id, String name, String manifestJson, UUID actorId) {
        jdbc.update("""
                INSERT INTO project_drafts (id, tenant_id, name, manifest_json, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, tenantId, name, manifestJson, actorId, actorId);
    }

    public Optional<DraftRow> find(UUID tenantId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, name, manifest_json, revision, created_at, updated_at
                    FROM project_drafts WHERE tenant_id = ? AND id = ?
                    """, ProjectDraftStore::draft, tenantId, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<DraftSummary> list(UUID tenantId) {
        return jdbc.query("""
                SELECT id, name, revision, created_at, updated_at
                FROM project_drafts WHERE tenant_id = ? ORDER BY updated_at DESC, id DESC LIMIT 100
                """, ProjectDraftStore::summary, tenantId);
    }

    public DraftPage page(UUID tenantId, int limit, DraftCursor before) {
        List<DraftSummary> rows = before == null
                ? jdbc.query("""
                        SELECT id, name, revision, created_at, updated_at
                        FROM project_drafts WHERE tenant_id = ?
                        ORDER BY updated_at DESC, id DESC LIMIT ?
                        """, ProjectDraftStore::summary, tenantId, limit + 1)
                : jdbc.query("""
                        SELECT id, name, revision, created_at, updated_at
                        FROM project_drafts
                        WHERE tenant_id = ? AND (updated_at < ? OR (updated_at = ? AND id < ?))
                        ORDER BY updated_at DESC, id DESC LIMIT ?
                        """, ProjectDraftStore::summary, tenantId,
                        Timestamp.from(before.updatedAt()), Timestamp.from(before.updatedAt()),
                        before.id(), limit + 1);
        if (rows.size() <= limit) return new DraftPage(rows, null);
        List<DraftSummary> items = List.copyOf(rows.subList(0, limit));
        DraftSummary last = items.get(items.size() - 1);
        return new DraftPage(items, new DraftCursor(last.updatedAt(), last.id()));
    }

    public int update(UUID tenantId, UUID id, long expectedRevision, String name,
                      String manifestJson, UUID actorId) {
        return jdbc.update("""
                UPDATE project_drafts SET name = ?, manifest_json = ?, revision = revision + 1,
                    updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """, name, manifestJson, actorId, tenantId, id, expectedRevision);
    }

    private static DraftRow draft(ResultSet rs, int row) throws SQLException {
        return new DraftRow(id(rs, "id"), rs.getString("name"), rs.getString("manifest_json"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static DraftSummary summary(ResultSet rs, int row) throws SQLException {
        return new DraftSummary(id(rs, "id"), rs.getString("name"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static UUID id(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public record DraftRow(UUID id, String name, String manifestJson, long revision,
                           Instant createdAt, Instant updatedAt) { }

    public record DraftSummary(UUID id, String name, long revision,
                               Instant createdAt, Instant updatedAt) { }

    public record DraftCursor(Instant updatedAt, UUID id) { }

    public record DraftPage(List<DraftSummary> items, DraftCursor nextCursor) { }
}
