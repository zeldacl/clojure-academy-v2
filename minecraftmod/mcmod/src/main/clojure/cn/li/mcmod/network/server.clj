(ns cn.li.mcmod.network.server
  "Server-side RPC handler registry for GUI/network logic"
  (:require [cn.li.mcmod.gui.registry-contract :as registry-contract]
            [cn.li.mcmod.gui.owner-contract :as owner-contract]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; Network server handlers stored in Framework [:service :network-server]

(def ^:private server-path [:service :network-server])

(defn- network-server-state-snapshot []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom server-path {:handlers {} :frozen? false})
    {:handlers {} :frozen? false}))

(defn- update-network-server-state! [f & args]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in server-path
           (fn [current] (apply f (or current {:handlers {} :frozen? false}) args))))
  nil)

(defn- assert-not-frozen!
  []
  (when (:frozen? (network-server-state-snapshot))
    (throw (ex-info "Network server handlers are frozen" {}))))

(defn register-handler
  "Register a request handler with an explicit registry contract.

  Args:
  - msg-id: string message identifier
  - handler-fn: (fn [payload player] response-map)
  - contract: optional map with :owner-spec and :payload-routing"
  ([msg-id handler-fn]
   (register-handler msg-id handler-fn (registry-contract/default-server-gui-handler-contract)))
  ([msg-id handler-fn contract]
   (registry-contract/validate-handler-fn! msg-id handler-fn)
   (let [entry (registry-contract/handler-entry handler-fn contract)]
     (assert-not-frozen!)
     (update-network-server-state!
       update :handlers
       (fn [registry]
         (if-let [existing (get registry msg-id)]
           (if (and (identical? (registry-contract/registered-handler-fn existing)
                                 handler-fn)
                    (registry-contract/contracts-compatible?
                     (registry-contract/registered-handler-contract existing)
                     (:contract entry)))
             registry
             (throw (ex-info "Conflicting network handler id"
                             {:msg-id msg-id
                              :existing (registry-contract/registered-handler-contract existing)
                              :new (:contract entry)})))
           (assoc registry msg-id entry))))
     (log/info "Registered network handler for" msg-id
               "owner-spec=" (:owner-spec (:contract entry)))
     nil)))

(defn freeze-handlers!
  []
  (update-network-server-state! assoc :frozen? true)
  nil)

(defn reset-handlers-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in server-path {:handlers {} :frozen? false}))
  nil)

(defn handlers-snapshot
  []
  (network-server-state-snapshot))

(defn- validate-payload-routing!
  [contract payload]
  (when (= :sync-routing (:payload-routing contract))
    (owner-contract/require-sync-routing payload))
  payload)

(defn handle-request
  "Handle an incoming request and send a response if needed.

  Args:
  - msg-id: string message identifier
  - request-id: int
  - payload: map
  - player: player entity
  - respond-fn: (fn [request-id response-map]) or nil"
  [msg-id request-id payload player respond-fn]
  (if-let [entry (get (:handlers (network-server-state-snapshot)) msg-id)]
    (let [handler (registry-contract/registered-handler-fn entry)
          contract (registry-contract/registered-handler-contract entry)]
      (try
        (let [payload* (validate-payload-routing! contract payload)
              response (handler payload* player)]
          (when (and respond-fn (>= request-id 0))
            (respond-fn request-id (or response {}))))
        (catch Exception e
          (log/error "Error handling request" msg-id ":" (ex-message e))
          (when (and respond-fn (>= request-id 0))
            (respond-fn request-id {:success false :error (ex-message e)})))))
    (do
      (log/warn "No handler registered for" msg-id)
      (when (and respond-fn (>= request-id 0))
        (respond-fn request-id {:success false :error "no-handler"})))))
