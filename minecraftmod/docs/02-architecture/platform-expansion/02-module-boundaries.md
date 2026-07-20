# Module boundaries

## Core projects

- `api`: external Java API contracts.
- `mcmod`: loader-neutral runtime and shared framework.
- `ac`: content and gameplay.

These projects must not import Minecraft, Forge, Fabric, or other loader APIs.

## Platform source components

- `platform-src/common`: shared target glue.
- `platform-src/minecraft/base`: Minecraft API code shared by supported Minecraft versions.
- `platform-src/minecraft/version/*`: version-specific Minecraft API differences.
- `platform-src/loader/*`: loader lifecycle, metadata, events, and loader-specific bindings.

Minecraft components must not enumerate loaders. Loader components own loader lifecycle and call shared bootstrap directly.
