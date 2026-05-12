(ns cn.li.mc1201.runtime.nbt-core
  "Shared NBT helpers for per-player runtime state persistence.

  Uses only standard MC APIs (CompoundTag, ServerPlayer) so this is
  loader-agnostic and can be used by both Forge and Fabric adapters.

  Storage format: EDN string under one CompoundTag key in player persistent data."
  (:require [cn.li.mcmod.runtime.hooks-core :as power-runtime]
            [cn.li.mc1201.runtime.edn-state :as es]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(def ^:private ability-state-key "ac_ability_state")

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (.getPersistentData player))

(defn load-player-state!
  "Load runtime state from persistent NBT into in-memory player-state atom."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        tag  (player-tag player)]
    (if (.contains tag ability-state-key)
      (if-let [m (es/decode-edn-safe
                  (.getString tag ability-state-key)
                  #(log/warn "Failed to decode runtime NBT EDN:" (ex-message %)))]
        (power-runtime/set-player-state! uuid (assoc m :dirty? false))
        (power-runtime/get-or-create-player-state! uuid))
      (power-runtime/get-or-create-player-state! uuid))))

(defn save-player-state!
  "Save in-memory runtime state to player persistent NBT."
  [^ServerPlayer player]
  (let [uuid  (str (.getUUID player))
        state (power-runtime/get-or-create-player-state! uuid)
        tag   (player-tag player)]
    (.putString tag ability-state-key (es/encode-edn (dissoc state :dirty?)))
    (power-runtime/mark-player-clean! uuid)
    true))

(defn clone-player-state!
  "Copy runtime state from original player into new cloned player entity.
  Called for PlayerEvent.Clone (Forge) or equivalent Fabric lifecycle event."
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [old-uuid (str (.getUUID old-player))
        new-uuid (str (.getUUID new-player))
        state    (or (power-runtime/get-player-state old-uuid)
                     (load-player-state! old-player)
                     (power-runtime/fresh-player-state))]
    (power-runtime/set-player-state! new-uuid (assoc state :dirty? true))
    (save-player-state! new-player)))
