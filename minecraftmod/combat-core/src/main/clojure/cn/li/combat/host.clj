(ns cn.li.combat.host
  "Startup-linked host table; Java only stores the function references."
  (:import [cn.li.mcmod.runtime.effect HostTable]
           [clojure.lang IFn]))

(set! *warn-on-reflection* true)

(defn build-host-table
  [capability-order query-handlers preflight-handler commit-handler]
  (when-not (ifn? preflight-handler)
    (throw (ex-info "missing preflight handler" {})))
  (when-not (ifn? commit-handler)
    (throw (ex-info "missing commit handler" {})))
  (HostTable.
    (object-array
      (mapv (fn [capability]
              (or (get query-handlers capability)
                  (throw (ex-info "missing query handler"
                                  {:capability capability}))))
            capability-order))
    preflight-handler
    commit-handler))

(defn build-host-table-from-capabilities
  [capability-state]
  (let [queries (:queries capability-state)
        actions (:actions capability-state)
        order (vec (sort (keys queries)))]
    (build-host-table
      order
      queries
      (or (:txn/preflight actions) (fn [_] true))
      (or (:txn/commit actions) (fn [_ _] true)))))

(defn invoke-query! [^HostTable host ^long capability-id request output]
  (let [^objects handlers (.-queryHandlers host)
        ^IFn handler (aget handlers capability-id)]
    (.invoke handler request output)))

(defn invoke-query-capability!
  "Invoke a neutral query by keyword from a compiled HostTable.  The table
  keeps only startup-linked function arrays; `capability-order` is the stable
  keyword order owned by the execution context."
  [^HostTable host capability-order capability request output]
  (let [capability-id (.indexOf ^java.util.List capability-order capability)
        ^objects handlers (.-queryHandlers host)]
    (when-not (and (<= 0 (long capability-id))
                   (< (long capability-id) (alength handlers)))
      (throw (ex-info "missing query capability"
                      {:capability capability})))
    (.invoke ^clojure.lang.IFn (aget handlers capability-id)
             request output)))

(defn invoke-preflight! [^HostTable host transaction]
  (let [^IFn handler (.-preflightHandler host)]
    (.invoke handler transaction)))

(defn invoke-commit! [^HostTable host transaction output]
  (let [^IFn handler (.-commitHandler host)]
    (.invoke handler transaction output)))
