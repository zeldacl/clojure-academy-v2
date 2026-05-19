(ns cn.li.mc1201.runtime.nbt-core
  "Shared NBT helpers for per-player runtime state persistence.

  Uses only standard MC APIs (CompoundTag, ServerPlayer) so this is
  loader-agnostic and can be used by both Forge and Fabric adapters.

  Storage format: EDN string under one CompoundTag key in player persistent data."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.runtime.edn-state :as es]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(def ^:private ability-state-key "ac_ability_state")
(def ^:private saved-locations-key "SavedLocations")

(defn- deep-merge-state
  [& maps]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (deep-merge-state a b)
             b))
         maps))

(defn- fresh-state-or-empty
  []
  (or (power-runtime/fresh-player-state) {}))

(defn- normalize-loaded-state
  [decoded]
  (when (map? decoded)
    (assoc (deep-merge-state (fresh-state-or-empty) decoded) :dirty? false)))

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (ru/inst player "getPersistentData"))

(defn load-player-state!
  "Load runtime state from persistent NBT into in-memory player-state atom."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        tag  (player-tag player)]
    (if (.contains tag ability-state-key)
      (let [decoded (es/decode-edn-safe
                     (.getString tag ability-state-key)
                     #(log/warn "Failed to decode runtime NBT EDN:" (ex-message %)))]
        (if-let [state (normalize-loaded-state decoded)]
          (power-runtime/set-player-state! uuid state)
          (do
            (when (some? decoded)
              (log/warn "Ignoring runtime NBT EDN with non-map root:" (pr-str (type decoded))))
            (power-runtime/get-or-create-player-state! uuid))))
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

(defn- clone-saved-locations!
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [^CompoundTag old-tag (player-tag old-player)]
    (when (.contains old-tag saved-locations-key)
      (let [^CompoundTag new-tag (player-tag new-player)
            ^CompoundTag locations-tag (.getCompound old-tag saved-locations-key)]
        (.put new-tag saved-locations-key (.copy locations-tag))))))

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
    (save-player-state! new-player)
    (clone-saved-locations! old-player new-player)))
