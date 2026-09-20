package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HttpApiServerBadRequestContractTest {
  @Test
  void invalidHandlerInputBecomesBadRequestWithoutLeakingServerErrors() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    int illegalArgument = source.indexOf("catch (IllegalArgumentException error)");
    int badRequest = source.indexOf("Map.of(\"error\", \"bad_request\")", illegalArgument);
    int genericFailure = source.indexOf("throw error;", illegalArgument);

    assertTrue(illegalArgument >= 0 && badRequest > illegalArgument);
    assertFalse(genericFailure > illegalArgument && genericFailure < source.indexOf("catch (Exception e)", illegalArgument));
  }
}
