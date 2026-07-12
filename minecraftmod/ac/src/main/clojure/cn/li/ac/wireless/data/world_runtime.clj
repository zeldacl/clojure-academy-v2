(ns cn.li.ac.wireless.data.world-runtime
  (:require [cn.li.ac.wireless.config :as network-config]
            [cn.li.ac.wireless.core.scheduling :as sched]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.data.network-runtime :as network-runtime]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.store :as store]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.runtime.node-transfer :as node-transfer]
            [cn.li.mcmod.platform.world :as platform-world]
            [cn.li.mcmod.util.log :as log]))

(defn network-impl-validator
  "Unregister disposed networks and networks whose matrix block is gone."
  [world-data world]
  (doseq [item (vals (world-registry/networks world-data))]
    (when (or (network-state/is-disposed? item)
              (and (vb/is-chunk-loaded? (:matrix item) world)
                   (nil? (resolver/resolve-matrix-cap world (:matrix item)))))
      (store/unregister-network! world-data item))))

(defn node-connection-impl-validator
  "Unregister disposed node connections."
  [world-data world]
  (doseq [item (vals (world-registry/connections world-data))]
    (when (node-conn/is-disposed? item)
      (store/unregister-connection! world-data item))))

(defn tick-world-data!
  "Advance all wireless runtime state for one server tick.

  Builds a per-tick ctx once — game time, config snapshot, and a tick-scoped
  capability cache (java.util.HashMap; single-threaded, discarded at tick
  end) — and threads it through network and connection ticking. Disposed
  entities are swept out periodically instead of only at world save."
  [world-data world]
  (let [state (world-registry/world-state world-data)
        cfg (network-config/cfg)
        game-time (long (or (platform-world/world-get-game-time* world) 0))
        ctx {:game-time game-time
             :cfg cfg
             :cap-cache (java.util.HashMap.)}]
    (doseq [net (vals (get state :networks))]
      (network-runtime/tick-wireless-net! net world ctx))
    (doseq [conn (vals (get state :connections))]
      (when-not (node-conn/is-disposed? conn)
        (node-transfer/tick-node-conn! conn world ctx)))
    (when (sched/due? game-time (long (get cfg :sweep-interval-ticks)) ::sweep)
      (network-impl-validator world-data world)
      (node-connection-impl-validator world-data world))))

(defn get-statistics
  "Get statistics about this world's wireless system."
  [world-data]
  {:networks (count (world-registry/networks world-data))
   :connections (count (world-registry/connections world-data))})

(defn print-statistics
  "Print statistics to log."
  [world-data]
  (let [stats (get-statistics world-data)]
    (log/info "=== Wireless System Statistics ===")
    (log/info (format "Networks: %d" (:networks stats)))
    (log/info (format "Connections: %d" (:connections stats)))))
