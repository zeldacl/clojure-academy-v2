(ns cn.li.forge1201.ability.nbt
  "NBT bridge for per-player ability state.

  Keeps persistence format simple: the whole state map is stored as an EDN
  string under one key in the player's persistent data tag.

  This is intentionally platform-layer code (forge module) because it touches
  CompoundTag/ServerPlayer APIs."
  (:require [clojure.edn :as edn]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(set! *warn-on-reflection* true)

(def ^:private ability-state-key "ac_ability_state")

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (.getPersistentData player))

(defn- safe-read-edn [s]
  (try
    (edn/read-string s)
    (catch Exception e
      (log/warn "Failed to decode ability NBT EDN:" (ex-message e))
      nil)))

(defn load-player-state!
  "Load ability state from persistent NBT into in-memory player-state atom."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        tag  (player-tag player)]
    (if (.contains tag ability-state-key)
      (if-let [m (safe-read-edn (.getString tag ability-state-key))]
        (ps/set-player-state! uuid (assoc m :dirty? false))
        (ps/get-or-create-player-state! uuid))
      (ps/get-or-create-player-state! uuid))))

(defn save-player-state!
  "Save in-memory ability state to player persistent NBT."
  [^ServerPlayer player]
  (let [uuid  (str (.getUUID player))
        state (ps/get-or-create-player-state! uuid)
        tag   (player-tag player)]
    (.putString tag ability-state-key (pr-str (dissoc state :dirty?)))
    (ps/mark-clean! uuid)
    true))

(defn clone-player-state!
  "Copy ability state from original player into new cloned player entity.
  Called for PlayerEvent.Clone."
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [old-uuid (str (.getUUID old-player))
        new-uuid (str (.getUUID new-player))
        state    (or (ps/get-player-state old-uuid)
                     (load-player-state! old-player)
                     (ps/fresh-state))]
    (ps/set-player-state! new-uuid (assoc state :dirty? true))
    (save-player-state! new-player)))
