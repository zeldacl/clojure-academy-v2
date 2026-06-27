(ns cn.li.mcmod.server.platform-bridge
  "Platform-neutral server bridge injected by platform adapters.

  The bridge provides server-side operations that content modules call
  without compile-time dependencies on mc-1.20.1 namespaces.

  Platform adapters install handlers via `install-server-bridge!`."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *server-bridge-ops* nil)

(defn- bridge-op [k & args]
  (when-let [ops *server-bridge-ops*]
    (when-let [f (get ops k)]
      (apply f args))))

(defn install-server-bridge!
  "Install server bridge callbacks from a map of handler functions."
  [{:keys [send-to-client! spawn-item-stack-at!]}]
  (prt/install-impl! #'*server-bridge-ops*
                     {:send-to-client! send-to-client!
                      :spawn-item-stack-at! spawn-item-stack-at!}
                     "server-bridge")
  nil)

(defn server-bridge-available? []
  (prt/impl-available? #'*server-bridge-ops*))

(defn call-with-server-bridge [ops f]
  (binding [*server-bridge-ops* ops] (f)))

(defn reset-server-bridge-for-test!
  []
  (alter-var-root #'*server-bridge-ops* (constantly nil))
  nil)

;; ============================================================================
;; Server bridge operations
;; ============================================================================

(defn send-to-client!
  "Send a network packet to a specific client player.
   player-uuid is the player's UUID string, message-key identifies the packet type,
   payload is arbitrary data."
  [player-uuid message-key payload]
  (or (bridge-op :send-to-client! player-uuid message-key payload)
      (log/debug "Server bridge send-to-client! not available")))

(defn spawn-item-stack-at!
  "Spawn an item stack entity in the world at a position.
   player is the player object, world-id identifies the world,
   x y z are world coordinates, item-stack is the item descriptor map."
  [player world-id x y z item-stack]
  (or (bridge-op :spawn-item-stack-at! player world-id x y z item-stack)
      (log/debug "Server bridge spawn-item-stack-at! not available")))
