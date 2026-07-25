package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.Announcement;
import io.github.addxiaoyi.starx.common.model.Punishment;
import io.github.addxiaoyi.starx.common.model.Report;
import io.github.addxiaoyi.starx.common.model.StaffNote;
import io.github.addxiaoyi.starx.common.model.StaffVote;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcRepositorySmokeTest {

  @TempDir
  Path tempDir;

  private SQLiteDataSource source;

  @BeforeEach
  void setUp() throws Exception {
    this.source = new SQLiteDataSource();
    this.source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("repositories.db").toAbsolutePath());
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE starx_announcements (id TEXT PRIMARY KEY, title TEXT, content TEXT, created_by TEXT, created_at BIGINT, expires_at BIGINT)");
      statement.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT, read_at BIGINT, PRIMARY KEY (announcement_id, player_uuid))");
      statement.execute("CREATE TABLE starx_punishments (id TEXT PRIMARY KEY, target_uuid TEXT, target_name TEXT, type TEXT, reason TEXT, staff_uuid TEXT, staff_name TEXT, created_at BIGINT, expires_at BIGINT, active BOOLEAN)");
      statement.execute("CREATE TABLE starx_staff_notes (id TEXT PRIMARY KEY, target_uuid TEXT, note TEXT, severity TEXT, staff_uuid TEXT, created_at BIGINT)");
      statement.execute("CREATE TABLE starx_reports (id TEXT PRIMARY KEY, reporter_uuid TEXT, target_uuid TEXT, category TEXT, details TEXT, status TEXT, resolved_by TEXT, resolved_at BIGINT)");
      statement.execute("CREATE TABLE starx_staff_votes (id TEXT PRIMARY KEY, target_uuid TEXT, target_name TEXT, reason TEXT, vote_type TEXT, status TEXT, initiator_uuid TEXT, initiator_name TEXT, yes_votes INT, no_votes INT, required_yes INT, expires_at BIGINT, created_at BIGINT, resolved_at BIGINT)");
      statement.execute("CREATE TABLE starx_staff_vote_records (vote_id TEXT, voter_uuid TEXT, vote TEXT, voted_at BIGINT, PRIMARY KEY (vote_id, voter_uuid))");
    }
  }

  @Test
  void announcementQueriesAndReadTrackingWork() {
    JdbcAnnouncementRepository announcements = new JdbcAnnouncementRepository(this.source);
    UUID playerId = UUID.randomUUID();
    announcements.create(new Announcement("a1", "公告", "内容", "console", 10L, null));

    assertEquals("公告", announcements.findById("a1").orElseThrow().title());
    assertEquals(1, announcements.findActive().size());
    assertEquals(1, announcements.findUnreadByPlayer(playerId).size());
    announcements.markRead("a1", playerId);
    assertTrue(announcements.findUnreadByPlayer(playerId).isEmpty());
  }

  @Test
  void punishmentNoteAndReportQueriesWork() {
    UUID target = UUID.randomUUID();
    UUID staff = UUID.randomUUID();
    JdbcPunishmentRepository punishments = new JdbcPunishmentRepository(this.source);
    punishments.record(new Punishment(
        "p1", target, "Alex", "BAN", "测试", staff, "Admin", 20L, null, true));
    assertEquals("p1", punishments.findByPlayer(target).getFirst().id());
    assertEquals(1, punishments.findActiveByTargetUuid(target).size());
    punishments.deactivate("p1");
    assertFalse(punishments.findById("p1").orElseThrow().active());

    JdbcStaffNoteRepository notes = new JdbcStaffNoteRepository(this.source);
    notes.addNote(new StaffNote("n1", target, "观察记录", "INFO", staff, 30L));
    assertEquals("观察记录", notes.findByPlayer(target).getFirst().note());

    JdbcReportRepository reports = new JdbcReportRepository(this.source);
    reports.create(new Report(
        "r1", UUID.randomUUID(), target, "CHAT", "举报内容", "PENDING", null, null));
    assertEquals(1, reports.findByStatus("PENDING").size());
    reports.resolve("r1", staff.toString());
    assertEquals("RESOLVED", reports.findById("r1").orElseThrow().status());
  }

  @Test
  void staffVoteQueriesAndVoteCountingWork() {
    UUID target = UUID.randomUUID();
    UUID initiator = UUID.randomUUID();
    UUID voter = UUID.randomUUID();
    long now = System.currentTimeMillis();
    JdbcVoteRepository votes = new JdbcVoteRepository(this.source);
    votes.create(new StaffVote(
        "v1", target, "Alex", "测试投票", "BAN", "ACTIVE", initiator, "Admin",
        0, 0, 1, now + 60_000, now, null));

    assertEquals("v1", votes.findActive().orElseThrow().id());
    assertEquals(1, votes.findByInitiator(initiator).size());
    votes.castVote("v1", voter, true);
    assertTrue(votes.hasVoted("v1", voter));
    assertEquals(1, votes.countYes("v1"));
  }
}
