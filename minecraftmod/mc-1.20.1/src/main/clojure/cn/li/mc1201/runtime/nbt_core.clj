(ns cn.li.mc1201.runtime.nbt-core
  "Incompatible player persistence v2: one native NBT root, schema 2."
  (:require [cn.li.mc1201.runtime.native-nbt :as native-nbt]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.platform.player-persistent-data :as player-pd]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(def ^:private root-key "ac_runtime_v2")
(def ^:private schema-version 2)
(def ^:private persisted-domains
  [:ability-data :resource-data :preset-data :develop-data])

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (player-pd/get-persistent-data! player))

(defn- read-root
  [^CompoundTag player-data]
  (when (.contains player-data root-key 10)
    (let [^CompoundTag root (.getCompound player-data root-key)]
      (when (= schema-version (.getInt root "schema"))
        root))))

(defn- load-domains
  [^CompoundTag root]
  (if-not root
    {}
    (reduce (fn [result domain]
              (let [key (name domain)]
                (if (.contains root key 10)
                  (try
                    (assoc result domain
                           (native-nbt/decode-value (.getCompound root key)))
                    (catch Exception error
                      (log/warn "Ignoring invalid ac_runtime_v2 domain" key ":"
                                (ex-message error))
                      result))
                  result)))
            {}
            persisted-domains)))

(defn load-player-state!
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        loaded (load-domains (read-root (player-tag player)))
        state (merge (power-runtime/fresh-player-state) loaded)]
    (power-runtime/sync-player-state! uuid state)
    (or (power-runtime/get-player-state uuid) state)))

(defn save-player-state!
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        state (power-runtime/ensure-player-state! uuid)
        root (CompoundTag.)
        player-data (player-tag player)]
    (.putInt root "schema" schema-version)
    (doseq [domain persisted-domains]
      (when-let [domain-state (get state domain)]
        (.put root (name domain) (native-nbt/encode-value domain-state))))
    (.put player-data root-key root)
    (power-runtime/mark-player-clean! uuid)
    true))

(defn clone-player-state!
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [old-uuid (str (.getUUID old-player))
        new-uuid (str (.getUUID new-player))
        state (or (power-runtime/get-player-state old-uuid)
                  (load-player-state! old-player)
                  (power-runtime/fresh-player-state))]
    (power-runtime/sync-player-state! new-uuid state)
    (save-player-state! new-player)))
