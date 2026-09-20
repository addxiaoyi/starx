package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AdminFieldPolicyTest {
  @Test
  void limitsAnnouncementFieldsToPortableSchemaSizes() {
    assertEquals("t".repeat(128), AdminFieldPolicy.announcementTitle("t".repeat(128)));
    assertEquals("c".repeat(2048), AdminFieldPolicy.announcementContent("c".repeat(2048)));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.announcementTitle("t".repeat(129)));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.announcementContent("c".repeat(2049)));
  }

  @Test
  void limitsNotesAndReportsToPortableSchemaSizes() {
    assertEquals("n".repeat(1024), AdminFieldPolicy.staffNote("n".repeat(1024)));
    assertEquals("d".repeat(512), AdminFieldPolicy.reportDetails("d".repeat(512)));
    assertNull(AdminFieldPolicy.reportDetails(null));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.staffNote("n".repeat(1025)));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.reportDetails("d".repeat(513)));
  }

  @Test
  void requiresMinecraftNamesAndLimitsActorsToTheirColumns() {
    assertEquals("Steve", AdminFieldPolicy.minecraftName(" Steve ", "target_name"));
    assertEquals("console", AdminFieldPolicy.staffName(null));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.minecraftName(" ", "target_name"));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.staffName("a".repeat(17)));
    assertThrows(IllegalArgumentException.class,
        () -> AdminFieldPolicy.actorId("a".repeat(37), "created_by"));
  }
}
