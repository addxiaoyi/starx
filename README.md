# StarX

StarX is a Java 21 Minecraft network plugin for Velocity, Paper, and Folia. Version **0.3.0** introduces the universal three-platform JAR and the stable **StarX Extension API 1.0.0**.

## Downloads

The GitHub Release publishes exactly one deployable file: `starx-universal-0.3.0.jar`. Copy the same file into each independent Velocity, Paper, or Folia instance's `plugins/` directory.

## Build

```bash
./gradlew clean check \
  :starx-plugins:starx-universal:check \
  --warning-mode all \
  --no-daemon \
  --console=plain \
  --non-interactive
```

The build verifies pinned Velocity compile inputs, public API boundaries, extension lifecycle behavior, both platform descriptors, Folia support, duplicate ZIP entries, nested JAR leakage, and platform API leakage.

## Security automation

The `Security Analysis` workflow builds the final Universal JAR, verifies that Trivy identifies the packaged Gson, HikariCP, and SQLite JDBC components, scans packaged dependencies for HIGH/CRITICAL vulnerabilities, and scans repository source for HIGH/CRITICAL secrets and misconfiguration. Missing artifact coverage or any CRITICAL finding fails the gate; JSON reports and a Markdown summary are retained as workflow artifacts. CodeQL runs automatically when the repository is public or GitHub Code Security is enabled; GitHub does not provide CodeQL uploads for an ineligible private personal repository.

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
- `docs/COMPATIBILITY.md`
- `starx-plugins/starx-universal/README.md`
- `NOTICE`

## License

StarX-owned code is distributed under the GNU Affero General Public License v3. Embedded or derived third-party components retain their original licenses and notices. See `LICENSE`, `LICENSES/`, `NOTICE`, and source-file headers.
