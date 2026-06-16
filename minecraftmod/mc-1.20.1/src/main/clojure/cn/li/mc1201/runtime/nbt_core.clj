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

(defn- persistence-descriptors
  []
  (->> (power-runtime/list-player-persistence-descriptors)
       (sort-by (juxt #(long (or (:order %) 0)) (comp str :id)))))

(defn- runtime-state-descriptor
  []
  (first (filter #(and (= :runtime-state (:kind %))
                       (= :edn (:format %))
                       (:nbt-key %))
                 (persistence-descriptors))))

(defn- runtime-state-key
  []
  (:nbt-key (runtime-state-descriptor)))

(defn- normalize-loaded-state
  [decoded]
  (when (map? decoded)
    (assoc (deep-merge-state (fresh-state-or-empty) decoded) :dirty? false)))

(defn- player-tag
  ^CompoundTag [^ServerPlayer player]
  (player-pd/get-persistent-data! player))

(defn load-player-state!
  "Load runtime state from persistent NBT into in-memory player-state atom."
  [^ServerPlayer player]
  (let [uuid (str (.getUUID player))
        tag  (player-tag player)
        state-key (runtime-state-key)]
    (if (and state-key (.contains tag state-key))
      (let [decoded (es/decode-edn-safe
                     (.getString tag state-key)
                     #(log/warn "Failed to decode runtime NBT EDN:" (ex-message %)))]
        (if-let [state (normalize-loaded-state decoded)]
          (do
            (power-runtime/sync-player-state! uuid state)
            (or (power-runtime/get-player-state uuid) state))
          (do
            (when (some? decoded)
              (log/warn "Ignoring runtime NBT EDN with non-map root:" (pr-str (type decoded))))
            (power-runtime/ensure-player-state! uuid))))
      (power-runtime/ensure-player-state! uuid))))

(defn save-player-state!
  "Save in-memory runtime state to player persistent NBT."
  [^ServerPlayer player]
  (let [uuid  (str (.getUUID player))
      state (power-runtime/ensure-player-state! uuid)
        tag   (player-tag player)
        state-key (runtime-state-key)]
    (when state-key
      (.putString tag state-key (es/encode-edn (dissoc state :dirty?)))
      (power-runtime/mark-player-clean! uuid))
    (boolean state-key)))

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
