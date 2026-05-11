(ns cn.li.fabric1201.runtime.nbt
  "Fabric thin adapter for per-player runtime state NBT persistence.
  Delegates all logic to mc1201 nbt-core (CompoundTag/ServerPlayer are pure MC API)."
  (:require [cn.li.mc1201.runtime.nbt-core :as nbt-core])
  (:import [net.minecraft.server.level ServerPlayer]))

(defn load-player-state!
  "Load runtime state from persistent NBT into in-memory player-state atom."
  [^ServerPlayer player]
  (nbt-core/load-player-state! player))

(defn save-player-state!
  "Save in-memory runtime state to player persistent NBT."
  [^ServerPlayer player]
  (nbt-core/save-player-state! player))

(defn clone-player-state!
  "Copy runtime state from original player into new cloned player entity."
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (nbt-core/clone-player-state! old-player new-player))
