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
           open-screen open-simple-gui run-client-effect
           get-client-player screen-active? close-screen!
           send-system-message!]}]
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
                      :run-client-effect run-client-effect
                      :get-client-player get-client-player
                      :screen-active? screen-active?
                      :close-screen! close-screen!
                      :send-system-message! send-system-message!}
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

;; ============================================================================
;; Minecraft client access ops — avoids Class/forName reflection in ac layer
;; ============================================================================

(defn get-client-player
  "Return the current client-side Player instance, or nil."
  []
  (or (bridge-op :get-client-player)
      (do (log/debug "Client bridge get-client-player not available")
          nil)))

(defn screen-active?
  "Return true when any Minecraft screen is currently open."
  []
  (or (bridge-op :screen-active?)
      (do (log/debug "Client bridge screen-active? not available")
          false)))

(defn close-screen!
  "Close the current Minecraft screen (set to nil). No-op if no screen is open."
  []
  (or (bridge-op :close-screen!)
      (log/debug "Client bridge close-screen! not available")))

(defn send-system-message!
  "Send a translatable system message to a player.
  Args: [player translatable-key & format-args]"
  [player translatable-key & args]
  (or (apply bridge-op :send-system-message! player translatable-key args)
      (log/debug "Client bridge send-system-message! not available")))
