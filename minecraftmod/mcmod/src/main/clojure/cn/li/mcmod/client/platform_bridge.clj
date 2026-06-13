(ns cn.li.mcmod.client.platform-bridge
  "Platform-neutral client bridge injected by platform adapters.

  The bridge is intentionally content-agnostic: content modules choose screen
  and effect keys, while platform adapters provide generic host functions."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *client-bridge-ops* nil)

(defn- bridge-op [k & args]
  (when-let [ops *client-bridge-ops*]
    (when-let [f (get ops k)]
      (apply f args))))

(defn install-client-bridge!
  "Install client bridge callbacks from a map of handler functions."
  [{:keys [slot-key-down slot-key-tick slot-key-up slot-key-abort
           movement-key-down movement-key-tick movement-key-up
           open-screen open-simple-gui run-client-effect]}]
  (prt/install-impl! #'*client-bridge-ops*
                     {:slot-key-down slot-key-down
                      :slot-key-tick slot-key-tick
                      :slot-key-up slot-key-up
                      :slot-key-abort slot-key-abort
                      :movement-key-down movement-key-down
                      :movement-key-tick movement-key-tick
                      :movement-key-up movement-key-up
                      :open-screen open-screen
                      :open-simple-gui open-simple-gui
                      :run-client-effect run-client-effect}
                     "client-bridge")
  nil)

(defn client-bridge-available? []
  (prt/impl-available? #'*client-bridge-ops*))

(defn call-with-client-bridge [ops f]
  (binding [*client-bridge-ops* ops] (f)))

(defn reset-client-bridge-for-test!
  []
  (alter-var-root #'*client-bridge-ops* (constantly nil))
  nil)

(defn on-slot-key-down!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-down player-uuid key-idx)
      (log/debug "Client bridge slot-key-down not available")))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-tick player-uuid key-idx)
      (log/debug "Client bridge slot-key-tick not available")))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-up player-uuid key-idx)
      (log/debug "Client bridge slot-key-up not available")))

(defn on-slot-key-abort!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-abort player-uuid key-idx)
      (log/debug "Client bridge slot-key-abort not available")))

(defn on-movement-key-down!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-down player-uuid movement-key)
      (log/debug "Client bridge movement-key-down not available")))

(defn on-movement-key-tick!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-tick player-uuid movement-key)
      (log/debug "Client bridge movement-key-tick not available")))

(defn on-movement-key-up!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-up player-uuid movement-key)
      (log/debug "Client bridge movement-key-up not available")))

(defn open-screen!
  "Open a content-owned screen through the installed platform host."
  ([screen-key]
   (open-screen! screen-key nil))
  ([screen-key payload]
   (or (bridge-op :open-screen screen-key payload)
       (log/debug "Client bridge screen host not available" {:screen-key screen-key}))))

(defn open-simple-gui!
  ([gui-widget title]
   (or (bridge-op :open-simple-gui gui-widget title)
       (log/debug "Client bridge simple GUI not available")))
  ([gui-widget title opts]
   (or (bridge-op :open-simple-gui gui-widget title opts)
       (log/debug "Client bridge simple GUI not available"))))

(defn run-client-effect!
  "Run a content-owned local client effect through the installed platform host."
  ([effect-key]
   (run-client-effect! effect-key nil))
  ([effect-key payload]
   (or (bridge-op :run-client-effect effect-key payload)
       (log/debug "Client bridge effect host not available" {:effect-key effect-key}))))
