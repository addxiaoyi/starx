# StarX 1.1.9 build assets

This directory contains the reproducible local build produced from the `main` source tree.

- `starx-universal-1.1.9.jar`: primary cross-platform plugin artifact.
- `starx-velocity-1.1.9.jar`: Velocity-specific artifact.
- `SHA256SUMS.txt`: SHA-256 checksums for both JARs.
- `release-manifest.json`: version, platform, size, and checksum metadata.

The skin bridge changes keep refresh work on the bounded worker, apply the bound UUID first, preserve skin state across backend switches, and reject unsigned skin/cape delivery.
