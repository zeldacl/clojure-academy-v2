(ns cn.li.mc1201.runtime.nbt-core
  "Shared NBT helpers for per-player runtime state persistence.

  Uses only standard MC APIs (CompoundTag, ServerPlayer) so this is
  loader-agnostic and can be used by both Forge and Fabric adapters.

  Storage format is selected by content-owned player persistence descriptors."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.runtime.edn-state :as es]
            [cn.li.mcmod.platform.player-persistent-data :as player-pd]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(defn- persistence-descriptors
  []
  (->> (power-runtime/list-player-persistence-descriptors)
       (sort-by (fn [x] [(long (or (:order x) 0)) (str (:id x))]))))

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (player-pd/get-persistent-data! player))

;; Per-domain NBT key mapping is registered by content modules during init
;; via power-runtime/register-player-state-domain!. Read lazily at runtime.

(defn load-player-state!
  "Load each domain independently from per-domain NBT keys into in-memory atom."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        tag  (player-tag player)
        loaded (reduce-kv (fn [m domain-key nbt-key]
                            (if (.contains tag nbt-key)
                              (try
                                (let [decoded (es/decode-edn-safe (.getString tag nbt-key)
                                                                  #(log/warn "Failed to decode" nbt-key ":" (ex-message %)))]
                                  (if (map? decoded)
                                    (assoc m domain-key decoded)
                                    m))
                                (catch Exception e
                                  (log/warn "Error loading domain" nbt-key ":" (ex-message e))
                                  m))
                              m))
                          {}
                          (power-runtime/list-player-state-domains))]
    (power-runtime/sync-player-state! uuid (merge (power-runtime/fresh-player-state) loaded {:dirty? false}))
    (or (power-runtime/get-player-state uuid)
        (power-runtime/fresh-player-state))))

(defn save-player-state!
  "Save each domain independently to per-domain NBT keys."
  [^ServerPlayer player]
  (let [uuid  (str (.getUUID player))
        state (power-runtime/ensure-player-state! uuid)
        tag   (player-tag player)]
    (doseq [[domain-key nbt-key] (power-runtime/list-player-state-domains)]
      (when-let [domain-state (get state domain-key)]
        (try
          (.putString tag nbt-key (es/encode-edn domain-state))
          (catch Exception e
            (log/warn "Error saving domain" nbt-key ":" (ex-message e))))))
    (power-runtime/mark-player-clean! uuid)
    true))

(defn- clone-content-owned-tags!
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [^CompoundTag old-tag (player-tag old-player)]
    (doseq [{:keys [nbt-key format clone?]} (persistence-descriptors)
            :when (and clone? nbt-key (not= :edn format) (.contains old-tag nbt-key))]
      (let [^CompoundTag new-tag (player-tag new-player)
            ^CompoundTag content-tag (.getCompound old-tag nbt-key)]
        (.put new-tag nbt-key (.copy content-tag))))))

(defn clone-player-state!
  "Copy runtime state from original player into new cloned player entity.
  Called for PlayerEvent.Clone (Forge) or equivalent Fabric lifecycle event."
  [^ServerPlayer old-player ^ServerPlayer new-player]
  (let [old-uuid (str (.getUUID old-player))
        new-uuid (str (.getUUID new-player))
        state    (or (power-runtime/get-player-state old-uuid)
                     (load-player-state! old-player)
                     (power-runtime/fresh-player-state))]
      (power-runtime/sync-player-state! new-uuid (assoc state :dirty? true))
    (save-player-state! new-player)
    (clone-content-owned-tags! old-player new-player)))
