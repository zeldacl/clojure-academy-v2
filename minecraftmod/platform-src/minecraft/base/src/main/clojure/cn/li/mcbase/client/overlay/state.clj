(ns cn.li.mcbase.client.overlay.state
  (:require [cn.li.mcbase.client.session :as client-session]))

(defn create-overlay-state-runtime
  []
  {::runtime ::overlay-state-runtime
   :client-activated-overlay* (atom {})})

(def ^:private overlay-state-runtime-atom (atom (create-overlay-state-runtime)))

(defn- overlay-state-runtime?
  [runtime]
  (and (map? runtime)
       (= ::overlay-state-runtime (::runtime runtime))
       (some? (:client-activated-overlay* runtime))))

(defn call-with-overlay-state-runtime
  "Set the overlay state runtime for the current context (primarily for testing)."
  [runtime f]
  (when-not (overlay-state-runtime? runtime)
    (throw (ex-info "Expected overlay state runtime"
                    {:runtime runtime})))
  (let [saved @overlay-state-runtime-atom]
    (try
      (reset! overlay-state-runtime-atom runtime)
      (f)
      (finally
        (reset! overlay-state-runtime-atom saved)))))

(defmacro with-overlay-state-runtime
  [runtime & body]
  `(call-with-overlay-state-runtime ~runtime (fn [] ~@body)))

(defn- current-overlay-state-runtime
  []
  @overlay-state-runtime-atom)

(defn- client-activated-overlay-atom
  []
  (:client-activated-overlay* (current-overlay-state-runtime)))

(defn resolve-client-owner
  "Coerce a uuid string, {:player-uuid ...}, or full session owner into a
   client owner map with :client-session-id. AC's V-key hook historically
   passed only {:player-uuid ...}; owner-key requires both fields and would
   throw, so the combat HUD never saw the immediate overlay activate."
  [owner]
  (cond
    (and (map? owner)
         (:client-session-id owner)
         (:player-uuid owner))
    owner

    (map? owner)
    (or (client-session/owner-for-player-uuid
          (or (:player-uuid owner) (:uuid owner)))
        (client-session/current-local-player-owner))

    (some? owner)
    (or (client-session/owner-for-player-uuid owner)
        (client-session/current-local-player-owner))

    :else
    (client-session/current-local-player-owner)))

(defn get-client-activated
  [owner]
  (when-let [o (resolve-client-owner owner)]
    (get @(client-activated-overlay-atom) (client-session/owner-key o))))

(defn set-client-activated!
  [owner v]
  (when-let [o (resolve-client-owner owner)]
    (swap! (client-activated-overlay-atom) assoc (client-session/owner-key o) (boolean v)))
  nil)

(defn clear-client-activated!
  [owner]
  (when-let [o (resolve-client-owner owner)]
    (swap! (client-activated-overlay-atom) dissoc (client-session/owner-key o)))
  nil)

;; ============================================================================
;; Session cleanup
;; ============================================================================

(defn clear-client-overlay-session!
  [client-session-id]
  (swap! (client-activated-overlay-atom)
         (fn [states]
           (into {}
                 (remove (fn [[[entry-session-id _player-uuid] _value]]
                           (= client-session-id entry-session-id))
                         states))))
  nil)

(defn overlay-state-snapshot
  []
  @(client-activated-overlay-atom))

(defn reset-client-activated-for-test!
  ([]
   (reset-client-activated-for-test! {}))
  ([snapshot]
   (reset! (client-activated-overlay-atom) (or snapshot {}))
   nil))
