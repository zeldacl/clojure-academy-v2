(ns cn.li.ac.block.machine.registration
  "Unified scripted machine registration: tile, container, capabilities, block, hooks."
  (:require [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-kind :as tile-kind]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.util.log :as log]))

(defn register-tile-spec!
  [{:keys [id registry-name blocks impl tile-kind tick-fn read-nbt-fn write-nbt-fn
           container capability-keys]}]
  (tdsl/register-tile!
    (tdsl/create-tile-spec
      id
      (cond-> {:registry-name registry-name
               :impl (or impl :scripted)
               :blocks blocks}
        tile-kind (assoc :tile-kind tile-kind)
        tick-fn (assoc :tick-fn tick-fn)
        read-nbt-fn (assoc :read-nbt-fn read-nbt-fn)
        write-nbt-fn (assoc :write-nbt-fn write-nbt-fn)
        container (assoc :container container)
        capability-keys (assoc :capability-keys capability-keys)))))

(defn register-tile-kind!
  [{:keys [tile-kind tick-fn read-nbt-fn write-nbt-fn]}]
  (tile-kind/register-tile-kind!
    tile-kind
    (cond-> {}
      tick-fn (assoc :tick-fn tick-fn)
      read-nbt-fn (assoc :read-nbt-fn read-nbt-fn)
      write-nbt-fn (assoc :write-nbt-fn write-nbt-fn))))

(defn declare-and-register-capabilities!
  "capabilities: [{:key :phase-generator :interface IWirelessGenerator :factory fn} ...]
  tile-ids: vector of tile id strings — returns capability key set for tile specs."
  [capabilities tile-ids]
  (doseq [{:keys [key interface factory]} capabilities]
    (when-not (platform-cap/get-capability-entry key)
      (platform-cap/declare-capability! key interface factory))
    (doseq [tile-id tile-ids]
      (tdsl/register-tile-capability-keys! tile-id key)))
  (set (map :key capabilities)))

(defn register-blocks!
  [block-specs]
  (doseq [spec block-specs]
    (bdsl/register-block! spec)))

(defn register-machine-hooks!
  [{:keys [network-handler client-renderer]}]
  (when network-handler (hooks/register-network-handler! network-handler))
  (when client-renderer (hooks/register-client-renderer! client-renderer)))

(defn init-machine!
  "Run one-time machine init behind defonce-guard.

  machine-spec keys:
  - :guard atom from defonce-guard
  - :log-label string
  - :before (fn []) optional
  - :tile-kind optional map for register-tile-kind!
  - :tiles vector of tile spec maps
  - :capabilities optional
  - :tile-ids for capability/container binding (defaults from :tiles :id)
  - :containers map tile-id -> container fns
  - :blocks vector of block specs (bdsl/create-block-spec results)
  - :network-handler fn
  - :client-renderer symbol"
  [{:keys [guard log-label before after tile-kind tiles capabilities tile-ids containers blocks
           network-handler client-renderer]}]
  (with-init-guard guard
    (when before (before))
    (when tile-kind (register-tile-kind! tile-kind))
    (let [ids (or tile-ids (mapv :id tiles))
          cap-keys (when (seq capabilities)
                     (declare-and-register-capabilities! capabilities ids))]
      (doseq [tile tiles]
        (register-tile-spec!
          (assoc tile
                 :container (get containers (:id tile))
                 :capability-keys (or (:capability-keys tile) cap-keys)))))
    (when blocks (register-blocks! blocks))
    (when after (after))
    (register-machine-hooks! {:network-handler network-handler
                              :client-renderer client-renderer})
    (log/info (str "Initialized " (or log-label "machine")))))
