(ns cn.li.ac.ability.final-runtime
  "AC composition root for the final authoritative combat engine.

   This namespace is deliberately Minecraft-free.  Platform adapters provide
   the neutral host and state callbacks; no legacy VM, recipe, interception,
   or VFX runtime is consulted. Catalog initialization is a hard ABI gate;
   a non-final registration aborts startup rather than creating a fallback.")

(defn- resolve-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final runtime dependency is unavailable" {:symbol symbol}))))

(defn create-runtime [{:keys [host state-provider commit-state! session-provider commit-session! remove-session!] :as options}]
  (when-not (map? host) (throw (ex-info "final runtime requires neutral host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final runtime requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final runtime requires commit-state!" {})))
  (let [create-engine (resolve-var 'cn.li.combat.final-engine/create-engine)]
    {:options options
     :remove-session! remove-session!
     :engine (create-engine {:host host
                             :state-provider state-provider
                             :commit-state! commit-state!
                             :session-provider session-provider
                             :commit-session! commit-session!})
     :catalog (atom nil)
     :scheduled (atom [])}))

(defn create-from-capabilities
  "Build the final host from mcmod's neutral capability snapshot.

   Query handlers receive plain request maps.  Action handlers are wrapped so
   the final host can preflight every command without invoking a mutating
   Minecraft operation; only the apply phase crosses the mcmod boundary."
  [{:keys [state-provider commit-state! session-provider commit-session! remove-session!] :as options}]
  (let [snapshot ((resolve-var 'cn.li.mcmod.runtime.capabilities/snapshot))
        create-host (resolve-var 'cn.li.mcmod.runtime.host/create)
        host (create-host
              {:queries (:queries snapshot)
               :actions (into {}
                              (map (fn [[capability handler]]
                                     [capability
                                      (fn [phase command context]
                                        (if (= :preflight phase)
                                          true
                                          (handler (merge (:args command)
                                                          {:owner (:owner command)
                                                           :world-id (:world-id command)}))))]))
                              (:actions snapshot))})]
    (create-runtime {:host host
                     :state-provider state-provider
                      :commit-state! commit-state!
                      :session-provider session-provider
                      :commit-session! commit-session!
                      :remove-session! remove-session!})))

(defn initialize! [runtime]
  (let [initialize-catalog (resolve-var 'cn.li.ac.ability.final-catalog-service/initialize!)]
    (reset! (:catalog runtime) (initialize-catalog))
    runtime))

(defn catalog-status [runtime]
  (let [status (resolve-var 'cn.li.ac.ability.final-catalog-service/migration-status)]
    (if @(:catalog runtime) (status) {:status :cold})))

(defn resolve-damage!
  "Resolve a neutral damage event through the final damage policy engine."
  [runtime raw-event]
  (let [resolve-event (resolve-var 'cn.li.combat.final-damage/resolve-event)
        policies (vec (mapcat (fn [[ability-id source]]
                                (map #(assoc % :ability-id ability-id)
                                      (:damage-policies source)))
                              (get-in @(:catalog runtime) [:combat :sources])))]
    (assoc (resolve-event policies raw-event) :status :accepted)))

(defn- registration [runtime ability-id]
  ((resolve-var 'cn.li.ac.ability.final-catalog-service/registration) ability-id))

(defn dispatch!
  "Execute one final graph intent. Unknown abilities are rejected."
  [runtime ability-id frame]
  (if-not @(:catalog runtime)
    {:status :rejected :reason :catalog-not-initialized}
    (let [entry (registration runtime ability-id)]
      (if (nil? entry)
        {:status :rejected :reason :unknown-ability :ability-id ability-id}
        (let [execute (resolve-var 'cn.li.combat.final-engine/execute!)
              result (assoc (execute (:engine runtime) (:compiled entry) frame)
                            :owner (:owner frame))]
          (when (and (:finish-session? result) (ifn? (:remove-session! runtime)))
            ((:remove-session! runtime) (:owner frame)))
          (swap! (:scheduled runtime)
                 into (map #(assoc % :ability-id ability-id :frame frame)
                           (:scheduled result)))
          result)))))

(defn tick!
  "Run scheduled final nodes whose target tick has arrived.  Scheduled nodes
   are compiled as a one-step final program, preserving the same host/state
   transaction boundary as an immediate dispatch."
  [runtime tick]
  (let [due (atom [])
        later (atom [])]
    (doseq [scheduled @(:scheduled runtime)]
      (if (<= (long (:tick scheduled)) (long tick))
        (swap! due conj scheduled)
        (swap! later conj scheduled)))
    (reset! (:scheduled runtime) @later)
    (let [compile (resolve-var 'cn.li.combat.final-compiler/compile-program)
          execute (resolve-var 'cn.li.combat.final-engine/execute!)]
      {:status :accepted
       :tick tick
       :results (mapv (fn [{:keys [node frame]}]
                        (execute (:engine runtime)
                                 (compile {:component :flow/sequence :steps [node]})
                                 (assoc frame :tick tick)))
                      @due)})))

(defn abort-owner!
  "Cancel scheduled final work for one owner.  This is the shared lifecycle
   boundary for disconnect/death/dimension-change/gui-close."
  [runtime owner]
  (swap! (:scheduled runtime)
         (fn [scheduled]
           (vec (remove #(= owner (get-in % [:frame :owner])) scheduled))))
  {:status :aborted :owner owner})

(defonce ^:private production-runtime* (atom nil))

(defn install-production!
  "Install the one server-side final runtime instance used by AC's
   composition root.  The caller supplies neutral state callbacks; this
   function owns no Minecraft objects and is safe to invoke once at startup."
  [{:keys [state-provider commit-state! session-provider commit-session! remove-session!]}]
  (let [runtime (create-from-capabilities {:state-provider state-provider
                                           :commit-state! commit-state!
                                           :session-provider session-provider
                                           :commit-session! commit-session!
                                           :remove-session! remove-session!})]
    (initialize! runtime)
    (reset! production-runtime* runtime)
    runtime))

(defn production-runtime [] @production-runtime*)

(defn dispatch-production! [owner ability-id intent]
  (if-let [runtime @production-runtime*]
    (dispatch! runtime ability-id
               {:owner owner
                :ability-id ability-id
                :world (or (:world-id intent) "minecraft:overworld")
                :tick (long (or (:server-tick intent) (:tick intent) 0))
                :seed (long (or (:activation-seed intent)
                                (hash [owner ability-id (:server-tick intent)])))
                :input (dissoc intent :owner :ability-id)})
    {:status :rejected :reason :final-runtime-not-installed}))
