package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminInputTest {
  @Test
  void requiredTextRejectsBlankValues() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdminInput.requiredText("  ", "id", 128));

    assertEquals("id is required", error.getMessage());
  }

  @Test
  void requiredTextRejectsOversizedValuesAfterTrimming() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdminInput.requiredText(" abcdef ", "id", 5));

    assertEquals("id too long (max 5 characters)", error.getMessage());
  }

  @Test
  void requiredTextReturnsTrimmedValue() {
    assertEquals("report-1", AdminInput.requiredText(" report-1 ", "id", 128));
  }

  @Test
  void enumValueNormalizesCaseAndWhitespace() {
    assertEquals(
        "RESOLVED",
        AdminInput.enumValue(" resolved ", "status", Set.of("PENDING", "RESOLVED")));
  }

  @Test
  void enumValueRejectsUnknownValues() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdminInput.enumValue("open", "status", Set.of("PENDING", "RESOLVED")));

    assertEquals("Invalid status. Must be one of: PENDING, RESOLVED", error.getMessage());
  }
}
