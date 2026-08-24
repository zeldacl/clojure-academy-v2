(ns cn.li.ac.ability.final-runtime
  "AC composition root for the final authoritative combat engine.

   This namespace is deliberately Minecraft-free.  Platform adapters provide
   the neutral host and state callbacks; no legacy VM, recipe, interception,
   or VFX runtime is consulted.  Source registrations that have not yet been
   lowered are rejected with an explicit migration status.")

(defn- resolve-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final runtime dependency is unavailable" {:symbol symbol}))))

(defn create-runtime [{:keys [host state-provider commit-state!] :as options}]
  (when-not (map? host) (throw (ex-info "final runtime requires neutral host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final runtime requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final runtime requires commit-state!" {})))
  (let [create-engine (resolve-var 'cn.li.combat.final-engine/create-engine)]
    {:options options
     :engine (create-engine {:host host
                             :state-provider state-provider
                             :commit-state! commit-state!})
     :catalog (atom nil)
     :scheduled (atom [])}))

(defn initialize! [runtime]
  (let [initialize-catalog (resolve-var 'cn.li.ac.ability.final-catalog-service/initialize!)]
    (reset! (:catalog runtime) (initialize-catalog))
    runtime))

(defn catalog-status [runtime]
  (let [status (resolve-var 'cn.li.ac.ability.final-catalog-service/migration-status)]
    (if @(:catalog runtime) (status) {:status :cold})))

(defn- registration [runtime ability-id]
  ((resolve-var 'cn.li.ac.ability.final-catalog-service/registration) ability-id))

(defn dispatch!
  "Execute one final graph intent.  Pending/unknown abilities never fall
   through to the old runtime; they return a stable protocol-level status."
  [runtime ability-id frame]
  (if-not @(:catalog runtime)
    {:status :rejected :reason :catalog-not-initialized}
    (let [entry (registration runtime ability-id)]
      (cond
        (nil? entry) {:status :rejected :reason :unknown-ability :ability-id ability-id}
        (not= :ready (:status entry))
        {:status :pending-final-node-migration :ability-id ability-id
         :reason (:compile-error entry)}
        :else
        (let [execute (resolve-var 'cn.li.combat.final-engine/execute!)
              result (execute (:engine runtime) (:compiled entry) frame)]
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
