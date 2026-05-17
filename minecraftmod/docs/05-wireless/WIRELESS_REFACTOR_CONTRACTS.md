# Wireless Refactor Contracts

> 状态标签：**现行**（重构约束）

This document defines the current wireless runtime contracts. The old split API/domain/service/persistence migration stack has been removed; do not add compatibility wrappers for it.

## 1. Public API Contract

- Public wireless queries and commands enter through `cn.li.ac.wireless.api`.
- GUI/block code should consume snapshots or API return values, not mutable `WirelessNet`/`NodeConn` atoms directly.
- Commands should call the service/topology functions behind the API instead of rebuilding indexes by hand.

## 2. Capability Boundary

- Runtime tile resolution belongs in `cn.li.ac.wireless.core.capability-resolver`.
- Callers should resolve matrix/node/generator/receiver capabilities through the resolver before invoking `IWireless*` methods.
- `cn.li.ac.wireless.core.vblock` is the Minecraft/NBT boundary for wireless VBlocks and delegates pure position logic to `cn.li.ac.foundation.vblock`.

## 3. World State Contract

- `WiWorldData` owns network lookup, node lookup, spatial index, networks, and node connections for one world.
- Multi-index mutations must run through `cn.li.ac.wireless.data.world-registry/transact!` or a helper that already uses it.
- Network/node/device unlink is immediate. Do not reintroduce lazy `to-remove-*` queues.

## 4. Persistence Contract

- Active persistence is `cn.li.ac.wireless.data.persistence` schema version `1`.
- Save serializes current networks and node connections to NBT; load reconstructs a fresh `WiWorldData` and rebuilds indexes.
- Position codecs live in `wireless.core.vblock`; complete world-state codecs live in `wireless.data.persistence`.

## 5. Config Contract

- Wireless descriptors and typed getters live in `cn.li.ac.wireless.config`.
- `cn.li.ac.config.registry` aggregates `wireless.config` plus non-wireless block descriptors such as solar.
- Do not add new wireless config namespaces under block or data packages.

## 6. Validation Gates

- Compile: `.\gradlew.bat :ac:compileClojure --no-daemon --console=plain`
- Unit tests: `.\gradlew.bat runAcUnitTests --no-daemon --console=plain`
- Architecture: `.\gradlew.bat verifyArchitectureBoundaries --no-daemon --console=plain`
