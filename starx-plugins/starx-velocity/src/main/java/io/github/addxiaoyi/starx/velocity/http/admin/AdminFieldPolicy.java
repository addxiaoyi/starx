package io.github.addxiaoyi.starx.velocity.http.admin;

final class AdminFieldPolicy {
  private AdminFieldPolicy() {}

  static String announcementTitle(String value) {
    return AdminInput.requiredText(value, "title", 128);
  }

  static String announcementContent(String value) {
    return AdminInput.requiredText(value, "content", 2048);
  }

  static String staffNote(String value) {
    return AdminInput.requiredText(value, "note", 1024);
  }

  static String reportDetails(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return AdminInput.requiredText(value, "details", 512);
  }

  static String minecraftName(String value, String field) {
    return AdminInput.requiredText(value, field, 16);
  }

  static String staffName(String value) {
    return AdminInput.requiredText(value == null ? "console" : value, "staff_name", 16);
  }

  static String actorId(String value, String field) {
    return AdminInput.requiredText(value, field, 36);
  }
}
