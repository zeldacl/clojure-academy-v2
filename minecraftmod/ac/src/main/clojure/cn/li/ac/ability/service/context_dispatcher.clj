(ns cn.li.ac.ability.service.context-dispatcher
  "Context transport router: listeners, routes, id counters, message buffering.

  Business context state (status, input-state, skill-state, keepalive) is
  authoritative in runtime-store and updated only via reducer commands."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-domain :as context-domain]
            [cn.li.ac.ability.service.context-projection :as ctx-proj]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.runtime.owner :as owner]
            [cn.li.mcmod.util.log :as log]))

(def STATUS-CONSTRUCTED context-domain/status-constructed)
(def STATUS-ALIVE context-domain/status-alive)
(def STATUS-TERMINATED context-domain/status-terminated)

(defn status-valid-transition? [from to]
  (context-domain/status-valid-transition? from to))

(def ^:private STATIC-ROUTE-OWNER [::static-routes ::process])

;; Context owner stored in Framework [:service :client-ctx :context-owner]
;; instead of ^:dynamic ThreadLocal. Replaced pattern: read via (*context-owner*).
(defn *context-owner*
  "Read context owner from Framework client context (or use provided owner, or nil)."
  ([]
   (get-in @(fw/fw-atom) [:service :client-ctx :context-owner]))
  ([owner]
   (or owner
       (get-in @(fw/fw-atom) [:service :client-ctx :context-owner]))))

(def ^:private default-dispatcher-state
  {:transport-contexts {}
   :route-fns          {}
   :client-id-counter  {}
   :server-id-counter  {}})
;; Context dispatcher — Framework [:service :context-dispatcher]

(def ^:private disp-path [:service :context-dispatcher])

(defn- dispatcher-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom disp-path)
        (let [a (atom default-dispatcher-state)]
          (swap! fw-atom assoc-in disp-path a) a))
    (atom default-dispatcher-state)))


(defn- dispatcher-state-snapshot
  []
  @(dispatcher-state-atom))

(defn- update-dispatcher-state!
  [f & args]
  (apply swap! (dispatcher-state-atom) f args))

(defn- transport-contexts-snapshot
  []
  (:transport-contexts (dispatcher-state-snapshot)))

(defn- context-store-session-id
  [ctx]
  (owner/transport-store-session-id ctx))

(defn- context-store-player-uuid
  [ctx]
  (some-> (:player-uuid ctx) str))

(defn- run-context-status-command!
  [ctx status reason]
  (let [session-id (context-store-session-id ctx)
        player-uuid (context-store-player-uuid ctx)]
    (when (and session-id player-uuid)
      (command-rt/run-command-in-session!
        session-id
        player-uuid
        {:command :update-context-status
         :ctx-id  (:id ctx)
         :status  status
         :reason  reason}))))

(defn- route-fns-snapshot
  []
  (:route-fns (dispatcher-state-snapshot)))

(defn- assoc-transport!
  [key ctx]
  (update-dispatcher-state! assoc-in [:transport-contexts key] ctx)
  ctx)

(defn- update-transport-if-present!
  [key f & args]
  (update-dispatcher-state!
    (fn [state]
      (if (contains? (:transport-contexts state) key)
        (apply update-in state [:transport-contexts key] f args)
        state))))

(defn- dissoc-transport!
  [key]
  (update-dispatcher-state! update :transport-contexts dissoc key)
  nil)

(defn- clear-route-fns!
  []
  (update-dispatcher-state! assoc :route-fns {})
  nil)

(defn- register-route-fns-entry!
  [owner-key routes]
  (update-dispatcher-state! assoc-in [:route-fns owner-key] routes)
  nil)

(defn- context-logical-side
  [ctx]
  (case (:logical-side ctx)
    (:client "client" :logical-side/client) :client
    (:server "server" :logical-side/server) :server
    (:any "any") :any
    (or (when (:server-id ctx) :server) :client)))

(defn- context-registry-key
  [ctx]
  [(context-logical-side ctx) (owner/transport-route-key ctx) (:id ctx)])

(defn- route-owner-key
  [owner-map]
  (let [logical-side (or (:logical-side owner-map) (:side owner-map))
        store (owner/store-session-id owner-map)]
    (if (and (nil? logical-side) (nil? store))
      STATIC-ROUTE-OWNER
      [(case logical-side
         (:client "client" :logical-side/client) :client
         (:server "server" :logical-side/server) :server
         (:any "any") :any
         logical-side)
       (owner/route-key owner-map logical-side)])))

(defn- context-owner-counter-key
  [owner-map logical-side]
  (let [side (case logical-side
               (:client "client" :logical-side/client) :client
               (:server "server" :logical-side/server) :server
               (:any "any") :any
               logical-side)]
    [side (owner/route-key owner-map side)]))

(defn- next-context-id!
  [counter-key owner prefix]
  (let [new-state (update-dispatcher-state!
                    (fn [state]
                      (let [next-counter (inc (get-in state [counter-key owner] 0))]
                        (assoc-in state [counter-key owner] next-counter))))
        next-counter (get-in new-state [counter-key owner])]
    (str prefix "-" next-counter)))

(defn- resolve-context-owner
  [owner ctx-id _preferred-side]
  (let [resolved-owner (or owner (*context-owner*))]
    (when-not resolved-owner
      (throw (ex-info "Opaque ctx-id resolution requires *context-owner* or an explicit owner"
                      {:ctx-id ctx-id})))
    resolved-owner))

(defn- preferred-context-entry
  ([ctx-id]
   (preferred-context-entry nil ctx-id nil))
  ([ctx-id preferred-side]
   (preferred-context-entry nil ctx-id preferred-side))
  ([owner ctx-id preferred-side]
   (if (vector? ctx-id)
     (when-let [ctx (get (transport-contexts-snapshot) ctx-id)]
       [ctx-id ctx])
     (let [resolved-owner (resolve-context-owner owner ctx-id preferred-side)
           lookup-side (or preferred-side (owner/logical-side resolved-owner))
           route-key (owner/route-key resolved-owner lookup-side)
           key [lookup-side route-key ctx-id]]
       (when-let [ctx (get (transport-contexts-snapshot) key)]
         [key ctx])))))

(defn- route-fns-for-context
  [ctx]
  (let [side-key [(context-logical-side ctx) (owner/transport-route-key ctx)]
        route-any-key [:any (owner/transport-route-key ctx)]]
    (or (get (route-fns-snapshot) side-key)
        (get (route-fns-snapshot) route-any-key)
        (get (route-fns-snapshot) STATIC-ROUTE-OWNER)
        {:to-server nil :to-client nil :to-except-local nil})))

(defn- as-public-context
  [ctx]
  (when ctx
    (owner/public-context (ctx-proj/merge-store-projection ctx))))

(declare register-context!)

(defn new-context
  ([player-uuid skill-id]
   (new-context player-uuid skill-id (*context-owner*)))
  ([player-uuid skill-id owner]
   (let [owner* (resolve-context-owner owner nil :client)
         counter-owner (context-owner-counter-key owner* :client)
         id (next-context-id! :client-id-counter counter-owner "cid")
         base {:id        id :server-id nil :player-uuid (str player-uuid) :skill-id skill-id
               :status    STATUS-CONSTRUCTED :input-state :idle :message-buffer []
               :listeners {} :last-keepalive-ms nil :terminated-at-ms nil}]
     (owner/attach-transport-owner-metadata! base owner* :client))))

(defn new-server-context
  ([player-uuid skill-id client-id]
   (new-server-context player-uuid skill-id client-id (*context-owner*)))
  ([player-uuid skill-id client-id owner]
   (let [owner* (resolve-context-owner owner nil :server)
         counter-owner (context-owner-counter-key owner* :server)
         sid (next-context-id! :server-id-counter counter-owner "sid")
         base {:id        client-id :server-id sid :player-uuid (str player-uuid) :skill-id skill-id
               :status    STATUS-ALIVE :input-state :idle :message-buffer []
               :listeners {} :last-keepalive-ms (System/currentTimeMillis) :terminated-at-ms nil}]
     (owner/attach-transport-owner-metadata! base owner* :server))))

(defn start-context!
  [player-uuid skill-id]
  (let [ctx (new-context player-uuid skill-id (*context-owner*))]
    (register-context! ctx)
    ctx))

(defn start-server-context!
  [player-uuid skill-id client-id]
  (let [ctx (new-server-context player-uuid skill-id client-id (*context-owner*))]
    (register-context! ctx)
    ctx))

(defn register-context! [ctx]
  (let [session-id (context-store-session-id ctx)
        player-uuid (context-store-player-uuid ctx)]
    (when (and session-id player-uuid)
      (command-rt/run-command-in-session!
        session-id
        player-uuid
        {:command  :register-context
         :ctx-id   (:id ctx)
         :skill-id (:skill-id ctx)
         :status   (or (:status ctx) STATUS-CONSTRUCTED)}))
    (assoc-transport! (context-registry-key ctx) ctx)
    ctx))

(defn get-context
  ([ctx-id]
   (as-public-context (second (preferred-context-entry nil ctx-id nil))))
  ([owner ctx-id]
   (if (vector? ctx-id)
     (as-public-context (get (transport-contexts-snapshot) ctx-id))
     (let [[side route-key] (route-owner-key owner)
           ctx (get (transport-contexts-snapshot) [side route-key ctx-id])]
       (as-public-context ctx)))))

(defn get-all-contexts
  []
  (into {}
        (map (fn [[k v]] [k (as-public-context v)]))
        (transport-contexts-snapshot)))

(defn remove-context!
  ([ctx-id]
   (remove-context! nil ctx-id))
  ([owner ctx-id]
   (if (vector? ctx-id)
     (dissoc-transport! ctx-id)
     (when-let [[key _ctx] (preferred-context-entry owner ctx-id nil)]
       (dissoc-transport! key)))))

(defn- owner-matches-context?
  [owner ctx]
  (let [[logical-side route-key] (route-owner-key owner)]
    (and (= logical-side (context-logical-side ctx))
         (= route-key (owner/transport-route-key ctx)))))

(defn get-all-contexts-for-player
  ([player-uuid]
   (filter #(= (str player-uuid) (:player-uuid %))
           (map as-public-context (vals (transport-contexts-snapshot)))))
  ([owner player-uuid]
   (->> (vals (transport-contexts-snapshot))
        (filter #(and (= (str player-uuid) (:player-uuid %))
                      (owner-matches-context? owner %)))
        (map as-public-context))))

(defn snapshot-context-registry []
  (get-all-contexts))

(defn snapshot-transport-contexts
  "Return full transport context maps including private owner metadata.
  Intended for internal lifecycle managers only."
  []
  (vals (transport-contexts-snapshot)))

(defn clear-owner-contexts!
  [owner]
  (let [owner-key (route-owner-key owner)]
    (update-dispatcher-state!
      (fn [state]
        (let [filtered-contexts (into {}
                                      (remove (fn [[_ctx-id ctx-map]]
                                                (= owner-key [(context-logical-side ctx-map)
                                                              (owner/transport-route-key ctx-map)])))
                                      (:transport-contexts state))]
          (-> state
              (assoc :transport-contexts filtered-contexts)
              (update :client-id-counter dissoc owner-key)
              (update :server-id-counter dissoc owner-key))))))
  nil)

(defn clear-store-session-contexts!
  "Clear all registered contexts and counters for one store session token."
  [store-session-id]
  (update-dispatcher-state!
    (fn [state]
      (-> state
          (update :transport-contexts
                  (fn [registry]
                    (into {}
                          (remove (fn [[_ctx-id ctx-map]]
                                    (= store-session-id (context-store-session-id ctx-map))))
                          registry)))
          (update :client-id-counter
                  (fn [counters]
                    (into {}
                          (remove (fn [[[_side route-key] _counter]]
                                    (= store-session-id (if (vector? route-key) (first route-key) route-key))))
                          counters)))
          (update :server-id-counter
                  (fn [counters]
                    (into {}
                          (remove (fn [[[_side route-key] _counter]]
                                    (= store-session-id (if (vector? route-key) (first route-key) route-key))))
                          counters))))))
  nil)

(defn reset-contexts-for-test!
  ([]
   (reset-contexts-for-test! {}))
  ([contexts]
   (update-dispatcher-state!
     (fn [state]
       (-> state
           (assoc :transport-contexts contexts)
           (assoc :client-id-counter {})
           (assoc :server-id-counter {}))))
   nil))

(defn reset-route-fns-for-test!
  []
  (clear-route-fns!)
  nil)

(defn context-owned-by?
  [ctx-id player-uuid]
  (when-let [ctx (get-context ctx-id)]
    (= (str player-uuid) (:player-uuid ctx))))

(defn transition-to-alive!
  ([ctx-id server-id flush-fn]
   (transition-to-alive! nil ctx-id server-id flush-fn))
  ([owner ctx-id server-id flush-fn]
   (when-let [[_key ctx] (preferred-context-entry owner ctx-id :client)]
     (let [merged (ctx-proj/merge-store-projection ctx)]
       (when (status-valid-transition? (:status merged) STATUS-ALIVE)
         (run-context-status-command! merged STATUS-ALIVE nil)
         (update-transport-if-present!
           _key
           (fn [c]
             (let [buffer (:message-buffer c)
                   alive-transport (context-domain/transition-to-alive
                                     c server-id (System/currentTimeMillis))]
               (when (and flush-fn (seq buffer))
                 (doseq [msg buffer] (flush-fn msg)))
               alive-transport))))
       (get-context owner ctx-id)))))

(defn terminate-context!
  ([ctx-id send-terminated-fn]
   (terminate-context! nil ctx-id send-terminated-fn))
  ([owner ctx-id send-terminated-fn]
   (let [resolved-owner (or owner (*context-owner*))
         entry (preferred-context-entry resolved-owner ctx-id nil)]
     (when-let [[_key transport] entry]
       (let [merged (ctx-proj/merge-store-projection transport)]
         (when (not= (:status merged) STATUS-TERMINATED)
           (run-context-status-command! merged STATUS-TERMINATED :dispatcher-terminated)
           (update-transport-if-present!
             _key
             context-domain/transition-to-terminated
             (System/currentTimeMillis))
           (when send-terminated-fn
             (when-let [ctx-owner (owner/canonical-owner-from-transport transport)]
               (binding [*context-owner* ctx-owner]
                 (send-terminated-fn (:id merged)))))
           (log/debug "Context terminated:" (:id merged))))))))

(defn abort-all-contexts-for-player!
  ([player-uuid send-terminated-fn]
   (doseq [ctx (get-all-contexts-for-player player-uuid)]
     (terminate-context! (:id ctx) send-terminated-fn)))
  ([owner player-uuid send-terminated-fn]
   (doseq [ctx (get-all-contexts-for-player owner player-uuid)]
     (terminate-context! owner (:id ctx) send-terminated-fn))))

(defn- require-bound-owner!
  [owner ctx-id op]
  (or owner *context-owner*
      (throw (ex-info (str op " requires explicit owner or bound *context-owner*")
                      {:ctx-id ctx-id}))))

(defn ctx-buffer-or-send!
  ([ctx-id msg send-fn]
   (ctx-buffer-or-send! nil ctx-id nil msg send-fn))
  ([ctx-id preferred-side msg send-fn]
   (ctx-buffer-or-send! nil ctx-id preferred-side msg send-fn))
  ([owner ctx-id preferred-side msg send-fn]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-buffer-or-send!")]
     (let [ctx (second (preferred-context-entry owner* ctx-id preferred-side))]
       (when ctx
         (let [merged (ctx-proj/merge-store-projection ctx)]
           (case (:status merged)
             :constructed (when-let [[key _] (preferred-context-entry owner* ctx-id preferred-side)]
                            (update-transport-if-present! key update :message-buffer conj msg))
             :alive (when send-fn (send-fn msg))
             nil)))))))

(defn ctx-send-to-local!
  ([ctx-id channel msg]
   (ctx-send-to-local! nil ctx-id channel msg))
  ([owner ctx-id channel msg]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-send-to-local!")]
     (when-let [transport (second (preferred-context-entry owner* ctx-id nil))]
       (doseq [h (get-in transport [:listeners channel] [])]
         (try (h msg) (catch Exception e (log/warn "Listener threw" (ex-message e)))))))))

(defn register-route-fns! [{:keys [to-server to-client to-except-local] :as routes}]
  (if (and (nil? (:logical-side routes))
           (nil? (:side routes))
           (nil? (owner/store-session-id routes))
           (nil? to-server)
           (nil? to-client)
           (nil? to-except-local))
    (clear-route-fns!)
    (register-route-fns-entry!
      (route-owner-key routes)
      {:to-server to-server :to-client to-client :to-except-local to-except-local}))
  nil)

(defn ctx-send-to-server!
  ([ctx-id channel msg]
   (ctx-send-to-server! nil ctx-id channel msg))
  ([owner ctx-id channel msg]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-send-to-server!")]
     (ctx-buffer-or-send! owner* ctx-id :client {:channel channel :payload msg}
                          (fn [m] (when-let [ctx (second (preferred-context-entry owner* ctx-id :client))]
                                    (when-let [f (:to-server (route-fns-for-context ctx))]
                                      (f ctx-id (:channel m) (:payload m) ctx))))))))

(defn ctx-send-to-client!
  ([ctx-id channel msg]
   (ctx-send-to-client! nil ctx-id channel msg))
  ([owner ctx-id channel msg]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-send-to-client!")]
     (ctx-buffer-or-send! owner* ctx-id :server {:channel channel :payload msg}
                          (fn [m] (when-let [ctx (second (preferred-context-entry owner* ctx-id :server))]
                                    (when-let [f (:to-client (route-fns-for-context ctx))]
                                      (f ctx-id (:channel m) (:payload m) ctx))))))))

(defn ctx-send-to-except-local!
  ([ctx-id channel msg]
   (ctx-send-to-except-local! nil ctx-id channel msg))
  ([owner ctx-id channel msg]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-send-to-except-local!")]
     (ctx-buffer-or-send! owner* ctx-id :server {:channel channel :payload msg}
                          (fn [m] (when-let [ctx (second (preferred-context-entry owner* ctx-id :server))]
                                    (when-let [f (:to-except-local (route-fns-for-context ctx))]
                                      (f ctx-id (:channel m) (:payload m) ctx))))))))

(defn ctx-send-to-self! [ctx-id channel msg] (ctx-send-to-local! ctx-id channel msg))

(defn ctx-on!
  ([ctx-id channel handler-fn]
   (ctx-on! nil ctx-id channel handler-fn))
  ([owner ctx-id channel handler-fn]
   (let [owner* (require-bound-owner! owner ctx-id "ctx-on!")]
     (when-let [[key _ctx] (preferred-context-entry owner* ctx-id nil)]
       (update-transport-if-present! key update-in [:listeners channel] (fnil conj []) handler-fn)))))

(defn active-context? [ctx]
  (context-domain/active-context? ctx))

(defn active-contexts
  ([]
   (->> (transport-contexts-snapshot)
        (map (fn [[_key ctx]] (as-public-context ctx)))
        (filter active-context?)
        (map (fn [ctx] [(:id ctx) ctx]))
        (into {})))
  ([player-uuid]
   (->> (get-all-contexts-for-player player-uuid)
        (filter active-context?))))

(defn send-context-message!
  ([ctx-id channel payload]
   (ctx-send-to-local! ctx-id channel payload))
  ([ctx-id direction channel payload]
   (case direction
     :to-server (ctx-send-to-server! ctx-id channel payload)
     :to-client (ctx-send-to-client! ctx-id channel payload)
     :to-except-local (ctx-send-to-except-local! ctx-id channel payload)
     :to-self (ctx-send-to-self! ctx-id channel payload)
     (throw (ex-info "Unsupported context message direction"
                     {:ctx-id    ctx-id
                      :direction direction
                      :allowed   [:to-server :to-client :to-except-local :to-self]})))))

(defn dispatch-skill-event!
  ([event]
   (evt/fire-ability-event! event))
  ([skill-id callback-key event]
   (evt/fire-ability-event!
     {:skill-id     skill-id
      :callback-key callback-key
      :event        event})))
