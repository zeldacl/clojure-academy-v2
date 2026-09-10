(ns cn.li.mcbase.runtime.nbt-core
  "Incompatible player persistence v2: one native NBT root, schema 2.

  CompoundTag reads go through cn.li.mcver.NbtAccess."
  (:require [cn.li.mcbase.runtime.native-nbt :as native-nbt]
            [cn.li.platform.neutral.hooks :as power-runtime]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcver NbtAccess]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(def ^:private root-key "ac_runtime_v2")
(def ^:private schema-version 2)
(def ^:private persisted-domains
  [:ability-data :resource-data :preset-data :develop-data :cheats-data :combat-data])

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :player-persistent-data :get! player)))

(defn- read-root
  [^CompoundTag player-data]
  (when (and player-data (NbtAccess/contains player-data root-key))
    (let [^CompoundTag root (NbtAccess/getCompound player-data root-key)]
      (when (= schema-version (NbtAccess/getInt root "schema"))
        root))))

(defn- load-domains
  [^CompoundTag root]
  (if-not root
    {}
    (reduce (fn [result domain]
              (let [key (name domain)]
                (if (NbtAccess/contains root key)
                  (try
                    (assoc result domain
                           (native-nbt/decode-value (NbtAccess/getCompound root key)))
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

(defn write-player-state!
  "Copy the in-memory runtime store into the player's persistent CompoundTag.
   Used by the normal logout/death/server-stop save path only."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        state (power-runtime/ensure-player-state! uuid)
        root (CompoundTag.)
        player-data (player-tag player)]
    (when-not player-data
      (throw (ex-info "Player persistent data unavailable for ac_runtime_v2 write"
                      {:uuid uuid})))
    (.putInt root "schema" schema-version)
    (doseq [domain persisted-domains]
      (when-let [domain-state (get state domain)]
        (.put root (name domain) (native-nbt/encode-value domain-state))))
    (.put ^CompoundTag player-data root-key root)
    true))

(defn save-player-state!
  [^ServerPlayer player]
  (write-player-state! player)
  (power-runtime/mark-player-clean! (str (.getUUID player)))
  true)

(defn save-all-players!
  "Flush every online player before the server session store is torn down."
  [^MinecraftServer server]
  (when server
    (doseq [^ServerPlayer player (.getPlayers (.getPlayerList server))]
      (try
        (save-player-state! player)
        (catch Exception error
          (log/warn "Failed to flush ac_runtime_v2 on server stop for"
                    (str (.getUUID player)) ":" (ex-message error))))))
  nil)

(defn clone-player-state!
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [old-uuid (str (.getUUID old-player))
        new-uuid (str (.getUUID new-player))
        state (or (power-runtime/get-player-state old-uuid)
                  (load-player-state! old-player)
                  (power-runtime/fresh-player-state))]
    (power-runtime/sync-player-state! new-uuid state)
    (save-player-state! new-player)))
