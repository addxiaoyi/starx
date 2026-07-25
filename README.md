# StarX

StarX is a Java 21 Minecraft network plugin for Velocity, Paper, and Folia. Version **0.2.0** introduces the universal three-platform JAR and the stable **StarX Extension API 1.0.0**.

## Downloads

Use `starx-universal-0.2.0.jar` for normal deployments. Copy the same file into each independent Velocity, Paper, or Folia instance's `plugins/` directory. Do not install the universal JAR together with a split platform JAR in the same instance.

Split artifacts are retained for platform-specific diagnostics:

- `starx-velocity-0.2.0.jar`
- `starx-server-0.2.0.jar`

## Build

```bash
./gradlew clean test \
  :starx-plugins:starx-api:build \
  :starx-plugins:starx-extension-example:build \
  :starx-plugins:starx-universal:check \
  --console=plain
```

The build verifies pinned Velocity compile inputs, public API boundaries, extension lifecycle behavior, both platform descriptors, Folia support, duplicate ZIP entries, nested JAR leakage, and platform API leakage.

## Extension API

Maven coordinates:

```text
io.github.addxiaoyi.starx:starx-api:1.0.0
```

Third-party plugins must use the API as `compileOnly` and must not shade or relocate it. See:

- `docs/EXTENSION_API.md`
- `docs/EXTENSION_COMPATIBILITY_POLICY.md`
- `starx-plugins/starx-extension-example/`

## Documentation

- `docs/STARX_PLATFORMS.md`
- `starx-plugins/starx-universal/README.md`
- `NOTICE`

## License

StarX-owned code is distributed under the GNU Affero General Public License v3. Embedded or derived third-party components retain their original licenses and notices. See `LICENSE`, `LICENSES/`, `NOTICE`, and source-file headers.
