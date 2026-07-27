package io.github.addxiaoyi.starx.website;

import java.util.Collection;

@FunctionalInterface
public interface TextureSource {
  Collection<PlayerTextureRecord> snapshot() throws Exception;

  static TextureSource empty() {
    return java.util.List::of;
  }
}
