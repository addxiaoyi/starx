# StarX Limbo upstream

## Fixed source

- Repository: `https://github.com/Elytrium/LimboAPI.git`
- Commit: `839773cfd406458cf247fbfd64ed492926f921b7`
- Upstream version line: `1.1.27-SNAPSHOT`
- Commit subject: `Fire LoginEvent with server ID hash instead of deprecated constructor (#264)`

StarX synchronizes source from this exact commit. A branch name, latest snapshot, tag-like version string, or newer dev-build is not an equivalent source.

## License boundary

The fixed commit contains two relevant license scopes:

- Implementation sources use AGPL-3.0 and retain their upstream headers.
- Sources from the upstream `api/` tree use the MIT License and retain their upstream headers.
- StarX-owned Uworld product code and embedded modifications carry StarX AGPL headers.

The checked-in license files are verbatim output from:

```powershell
git -C LimboAPI-source show 839773cfd406458cf247fbfd64ed492926f921b7:LICENSE
git -C LimboAPI-source show 839773cfd406458cf247fbfd64ed492926f921b7:api/LICENSE
```

They are stored at [AGPL-3.0](../../LICENSES/AGPL-3.0.txt) and [MIT](../../LICENSES/MIT.txt). Distribution attribution is in [NOTICE](../../NOTICE).

For LF-terminated UTF-8 content, the extracted files have these SHA-256 values:

```text
LICENSE      8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef
api/LICENSE  7b4e503c05dcd2b161a504ed5536637dd1dacbbc485a49781d86bfd592405123
```

## Vendored artifact

Generated protocol mappings come from the matching `dev-build` artifact stored at `starx-plugins/starx-standalone-limbo/vendor/limboapi-1.1.27-SNAPSHOT.jar`.

```text
SHA-256 18AC6287D413234C4FC317267A6D5DBF978ADAE8BF3F098A1248966BF2C32CE9
```

The JAR supplies mapping resources to the build; it is not deployed as an external or nested LimboAPI plugin.

## Synchronization

From the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/tests/sync-starx-limbo.Tests.ps1

powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/sync-starx-limbo.ps1
```

The sync script uses the checked-in vendor artifact by default. `-ArtifactPath` is allowed only when the replacement artifact has the pinned checksum reviewed for the same source revision.

The embedded runtime intentionally omits LimboAPI's login-stage event hook. StarX enters managed authentication Uworld flows only after Velocity login completes.

## Preserved overrides

The following StarX-owned embedded overrides are deliberately not replaced by the sync script:

- `src/main/java/io/github/addxiaoyi/starx/limbo/LimboAPI.java`
- `src/main/java/io/github/addxiaoyi/starx/limbo/server/LimboImpl.java`
- `src/main/java/io/github/addxiaoyi/starx/limbo/server/LimboPlayerImpl.java`
- `src/main/java/io/github/addxiaoyi/starx/limbo/server/CachedPackets.java`
- `src/main/java/io/github/addxiaoyi/starx/limbo/injection/packet/UpsertPlayerInfoHook.java`

They contain lifecycle ownership, initial-player state, observable Uworld transfer completion, cached-packet setup, concurrency changes, and Velocity compatibility changes that cannot be represented by package relocation alone. The sync script must fail before import if an override is missing.

## Upgrade checklist

1. Keep `LimboAPI-source` clean and fetch the proposed upstream commit.
2. Review the full diff from `839773cfd406458cf247fbfd64ed492926f921b7` to the proposed commit, including build logic, protocol mappings and both license files.
3. Extract `LICENSE` and `api/LICENSE` from the proposed commit and compare them byte-for-byte with `LICENSES/`.
4. Obtain the matching dev-build artifact, record its SHA-256, and do not reuse a JAR from another revision.
5. Rebase all five preserved overrides against their upstream counterparts before changing the pinned commit in the sync script.
6. Run the sync test, then the sync script, then inspect every imported source and mapping change.
7. Run `starx-limbo-api`, `starx-standalone-limbo`, `starx-common`, and `starx-velocity` tests from a clean build.
8. Build the final `starx-velocity.jar` and run JAR static checks for mappings, relocation, duplicate descriptors and nested JARs.
9. Update `NOTICE`, both README files, configuration/development docs and this file when provenance or behavior changes.
10. Complete Velocity cold-start and real-client acceptance before deploying. Unexecuted client rows remain `UNVERIFIED`.

Do not update only the commit string or only the vendor JAR. Uworld core is process-owned and does not support hot reload; validation requires a complete Velocity restart.
