(ns cn.li.mcmod.network.server
  "Server-side RPC handler registry for GUI/network logic"
  (:require [cn.li.mcmod.gui.registry-contract :as registry-contract]
            [cn.li.mcmod.util.log :as log]))

(defn default-network-server-runtime-state
  []
  {:handlers {}
   :frozen? false})

(defn create-network-server-runtime
  ([]
   (create-network-server-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-network-server-runtime-state))}}]
   {::runtime ::network-server-runtime
    :state* state*}))

(defonce ^:private installed-network-server-runtime
  (create-network-server-runtime))

(def ^:dynamic *network-server-runtime*
  installed-network-server-runtime)

(defn- network-server-runtime?
  [runtime]
  (and (map? runtime)
       (= ::network-server-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-network-server-runtime
  [runtime f]
  (when-not (network-server-runtime? runtime)
    (throw (ex-info "Expected network server runtime"
                    {:runtime runtime})))
  (binding [*network-server-runtime* runtime]
    (f)))

(defmacro with-network-server-runtime
  [runtime & body]
  `(call-with-network-server-runtime ~runtime (fn [] ~@body)))

(defn- current-network-server-runtime
  []
  *network-server-runtime*)

(defn- network-server-state-atom
  []
  (:state* (current-network-server-runtime)))

(defn- network-server-state-snapshot
  []
  @(network-server-state-atom))

(defn- update-network-server-state!
  [f & args]
  (apply swap! (network-server-state-atom) f args))

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
  (reset! (network-server-state-atom) (default-network-server-runtime-state))
  nil)

(defn handlers-snapshot
  []
  (network-server-state-snapshot))

(defn handle-request
  "Handle an incoming request and send a response if needed.

  Args:
  - msg-id: string message identifier
  - request-id: int
  - payload: map
  - player: player entity
  - respond-fn: (fn [request-id response-map]) or nil"
  [msg-id request-id payload player respond-fn]
  (if-let [handler (some-> (get (:handlers (network-server-state-snapshot)) msg-id)
                            registry-contract/registered-handler-fn)]
    (try
      (let [response (handler payload player)]
        (when (and respond-fn (>= request-id 0))
          (respond-fn request-id (or response {}))))
      (catch Exception e
        (log/error "Error handling request" msg-id ":"(ex-message e))
        (when (and respond-fn (>= request-id 0))
          (respond-fn request-id {:success false :error(ex-message e)}))))
    (do
      (log/warn "No handler registered for" msg-id)
      (when (and respond-fn (>= request-id 0))
        (respond-fn request-id {:success false :error "no-handler"})))))
