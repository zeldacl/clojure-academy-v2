(ns cn.li.mcmod.server.platform-bridge
  "Server bridge operations via Framework function map.

   Bridge ops stored at [:platform :server-bridge]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

;; Bootstrap writes this root once; server forwarding paths read the immutable
;; map directly instead of dereferencing the Framework atom on every send.
(def ^:private server-bridge-ops nil)


(defn install-server-bridge!
  "Install server bridge callbacks from a map of handler functions."
  [ops-map]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :server-bridge] ops-map))
  (install/install-root! #'server-bridge-ops ops-map)
  nil)

(defn server-bridge-available? []
  (boolean server-bridge-ops))

(defn reset-server-bridge-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :server-bridge] nil))
  (install/install-root! #'server-bridge-ops nil)
  nil)

(defn send-to-client!
  [player-uuid message-key payload]
  (or (when-let [f (get server-bridge-ops :send-to-client!)]
        (f player-uuid message-key payload))
      (log/debug "Server bridge send-to-client! not available")))

(defn spawn-item-stack-at!
  [world-id x y z item-id count]
  (let [f (get server-bridge-ops :spawn-item-stack-at!)]
    (when-not f
      (throw (ex-info "Required server bridge operation is not installed"
                      {:operation :spawn-item-stack-at!
                       :installed (keys server-bridge-ops)})))
    (f world-id x y z item-id count)))
