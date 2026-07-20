# Versioning strategy

Adding a Minecraft version or loader is explicit and incremental.

## Minecraft version

- Add a version component under `platform-src/minecraft/version/<component>`.
- Keep shared API code in `platform-src/minecraft/base`.
- Add the version component only to explicitly supported targets.

## Loader

- Add a loader component under `platform-src/loader/<loader>`.
- Keep loader lifecycle and metadata there.
- Do not add a platform bootstrap SPI.

## Target

- Add a `platform-targets.json` entry only when the combination is intentionally supported.
- Use synthetic fixture tests for architecture-only validation.
