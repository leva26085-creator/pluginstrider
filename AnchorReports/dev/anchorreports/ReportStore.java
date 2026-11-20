package dev.anchorreports;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.sqlite.JDBC;

final class ReportStore {
    static final int PAGE_SIZE = 45;

    record Draft(UUID reporterId, String reporter, UUID targetId, String target, ReportReason reason, long time) {}
    record Entry(long id, String reporter, String target, ReportReason reason, long time, String status, String reviewer) {}
    record Page(int number, long newestId, List<Entry> entries, boolean more) {}

    private final String url;
    private boolean initialized;

    ReportStore(Path path) {
        url = "jdbc:sqlite:" + path.toAbsolutePath();
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", "3000");
        Connection connection = new JDBC().connect(url, properties);
        if (!initialized) {
            try (var schema = connection.createStatement()) {
                schema.execute("PRAGMA journal_mode=WAL");
                schema.execute("BEGIN IMMEDIATE");
                schema.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id INTEGER PRIMARY KEY,
                        reporter_id TEXT NOT NULL,
                        reporter TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        target TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
                var columns=new java.util.HashSet<String>();try(var rows=schema.executeQuery("PRAGMA table_info(reports)")){while(rows.next())columns.add(rows.getString("name"));}
                if(!columns.contains("status"))schema.executeUpdate("ALTER TABLE reports ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN'");
                if(!columns.contains("reviewed_by"))schema.executeUpdate("ALTER TABLE reports ADD COLUMN reviewed_by TEXT");
                if(!columns.contains("reviewed_at"))schema.executeUpdate("ALTER TABLE reports ADD COLUMN reviewed_at INTEGER");
                schema.execute("COMMIT");
                initialized = true;
            } catch (SQLException exception) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }
        return connection;
    }

    void save(Draft report) throws SQLException {
        try (var connection = open();
             var statement = connection.prepareStatement("""
                 INSERT INTO reports (reporter_id, reporter, target_id, target, reason, created_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setString(1, report.reporterId().toString());
            statement.setString(2, report.reporter());
            statement.setString(3, report.targetId().toString());
            statement.setString(4, report.target());
            statement.setString(5, report.reason().name());
            statement.setLong(6, report.time());
            statement.executeUpdate();
        }
    }

    Page read(int number, long newestId) throws SQLException {
        try (var connection = open();
             var statement = connection.prepareStatement("""
                 SELECT id, reporter, target, reason, created_at, status, reviewed_by FROM reports
                 WHERE id <= ? ORDER BY id DESC LIMIT ? OFFSET ?
                 """)) {
            statement.setLong(1, newestId);
            statement.setInt(2, PAGE_SIZE + 1);
            statement.setLong(3, (long) (number - 1) * PAGE_SIZE);
            List<Entry> entries = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(new Entry(rows.getLong("id"), rows.getString("reporter"), rows.getString("target"),
                        ReportReason.valueOf(rows.getString("reason")), rows.getLong("created_at"),rows.getString("status"),rows.getString("reviewed_by")));
                }
            }
            boolean more = entries.size() > PAGE_SIZE;
            if (more) entries.removeLast();
            if (number == 1 && !entries.isEmpty()) newestId = entries.getFirst().id();
            return new Page(number, newestId, List.copyOf(entries), more);
        }
    }

    boolean review(long id,String status,String operator) throws SQLException {
        if(!status.equals("ACCEPTED")&&!status.equals("REJECTED"))throw new IllegalArgumentException("Invalid review state");
        try(var connection=open();var sql=connection.prepareStatement("UPDATE reports SET status=?,reviewed_by=?,reviewed_at=? WHERE id=? AND status='OPEN'")){
            sql.setString(1,status);sql.setString(2,operator);sql.setLong(3,System.currentTimeMillis());sql.setLong(4,id);return sql.executeUpdate()==1;
        }
    }
}
