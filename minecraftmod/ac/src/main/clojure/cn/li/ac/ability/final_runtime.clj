(ns cn.li.ac.ability.final-runtime
  "AC composition root for the final authoritative combat engine.

   This namespace is deliberately Minecraft-free.  Platform adapters provide
   the neutral host and state callbacks; no legacy VM, recipe, interception,
   or VFX runtime is consulted. Catalog initialization is a hard ABI gate;
   a non-final registration aborts startup rather than creating a fallback."
  (:require [cn.li.combat.api :as combat-api]
            [cn.li.ac.ability.final-catalog-service :as catalog-service]))

(defn- resolve-var [symbol]
  (or (requiring-resolve symbol)
      (throw (ex-info "final runtime dependency is unavailable" {:symbol symbol}))))

(defn- resolve-runtime-apis
  "Same :apis map shape every call site below already reads through
   (get-in runtime [:apis ...]) -- only how it's populated changed, from
   requiring-resolve string-symbol indirection (which existed only because
   combat-core had no facade) to real, compile-time-checked requires."
  []
  {:create-engine combat-api/create-engine
   :initialize-catalog catalog-service/initialize!
   :catalog-status catalog-service/catalog-status
   :resolve-damage combat-api/resolve-damage
   :registration catalog-service/registration
   :execute combat-api/execute!})

(defn create-runtime [{:keys [host state-provider commit-state! ability-state-provider commit-ability-state! remove-ability-state!] :as options}]
  (when-not (map? host) (throw (ex-info "final runtime requires neutral host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final runtime requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final runtime requires commit-state!" {})))
  (let [apis (resolve-runtime-apis)
        create-engine (:create-engine apis)]
    {:options options
     :apis apis
     :remove-ability-state! remove-ability-state!
     :engine (create-engine {:host host
                             :state-provider state-provider
                             :commit-state! commit-state!
                             :ability-state-provider ability-state-provider
                             :commit-ability-state! commit-ability-state!})
     :catalog (atom nil)
     :scheduled (atom (sorted-map))}))

(defn create-from-capabilities
  "Build the final host from mcmod's neutral capability snapshot.

   Query handlers receive plain request maps.  Action handlers are wrapped so
   the final host can preflight every command without invoking a mutating
   Minecraft operation; only the apply phase crosses the mcmod boundary."
  [{:keys [state-provider commit-state! ability-state-provider commit-ability-state! remove-ability-state!] :as options}]
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
                      :ability-state-provider ability-state-provider
                      :commit-ability-state! commit-ability-state!
                      :remove-ability-state! remove-ability-state!})))

(defn initialize! [runtime]
  (let [initialize-catalog (get-in runtime [:apis :initialize-catalog])
        assembled (initialize-catalog)]
    (reset! (:catalog runtime)
            (assoc assembled
                   :damage-policies
                   (vec (mapcat (fn [[ability-id source]]
                                  (map #(assoc % :ability-id ability-id)
                                       (:damage-policies source)))
                                (get-in assembled [:combat :sources])))))
    runtime))

(defn catalog-status [runtime]
  (let [status (get-in runtime [:apis :catalog-status])]
    (if @(:catalog runtime) (status) {:status :cold})))

(defn resolve-damage!
  "Resolve a neutral damage event through the final damage policy engine."
  [runtime raw-event]
  (let [resolve-event (get-in runtime [:apis :resolve-damage])
        policies (:damage-policies @(:catalog runtime))]
    (assoc (resolve-event policies raw-event) :status :accepted)))

(defn- registration [runtime ability-id]
  ((get-in runtime [:apis :registration]) ability-id))

(defn- scheduled-program [node]
  {:schema-version 1
   :program {:component :flow/sequence
             :kind :flow
             :steps [node]}
   :instructions 1})

(defn- enqueue-scheduled! [runtime ability-id frame entries]
  (let [entries (mapv #(-> %
                            (assoc :ability-id ability-id
                                   :frame frame
                                   :program (scheduled-program (:node %)))
                            (dissoc :node))
                     entries)]
    (swap! (:scheduled runtime)
           (fn [buckets]
             (reduce (fn [result entry]
                       (update result (long (:tick entry)) (fnil conj []) entry))
                     buckets
                     entries)))))

(defn dispatch!
  "Execute one final graph intent. Unknown abilities are rejected."
  [runtime ability-id frame]
  (if-not @(:catalog runtime)
    {:status :rejected :reason :catalog-not-initialized}
    (let [entry (registration runtime ability-id)]
      (if (nil? entry)
        {:status :rejected :reason :unknown-ability :ability-id ability-id}
        (let [execute (get-in runtime [:apis :execute])
              result (assoc (execute (:engine runtime) (:compiled entry) frame)
                            :owner (:owner frame))]
          (when (and (:finish-ability? result) (ifn? (:remove-ability-state! runtime)))
            ((:remove-ability-state! runtime) (:owner frame)))
          (enqueue-scheduled! runtime ability-id frame (:scheduled result))
          result)))))

(defn tick!
  "Run scheduled final nodes whose target tick has arrived.  Scheduled nodes
   already-compiled one-step programs, preserving the same host/state
   transaction boundary as an immediate dispatch."
  [runtime tick]
  (let [buckets @(:scheduled runtime)
        due-buckets (subseq buckets <= (long tick))]
    ;; The overwhelmingly common tick has no scheduled work.  Avoid building
    ;; transient/persistent collections and writing the atom in that case.
    (if-not (seq due-buckets)
      {:status :accepted :tick tick :results []}
      (let [due (persistent!
                 (reduce (fn [out [_ entries]]
                           (reduce conj! out entries))
                         (transient [])
                         due-buckets))
            later (reduce (fn [remaining [deadline _]]
                            (dissoc remaining deadline))
                          buckets
                          due-buckets)]
        (reset! (:scheduled runtime) later)
        (let [execute (get-in runtime [:apis :execute])]
          {:status :accepted
           :tick tick
           :results (mapv (fn [{:keys [program frame]}]
                            (execute (:engine runtime) program (assoc frame :tick tick)))
                          due)})))))

(defn abort-owner!
  "Cancel scheduled final work for one owner.  This is the shared lifecycle
   boundary for disconnect/death/dimension-change/gui-close."
  [runtime owner]
  (swap! (:scheduled runtime)
         (fn [buckets]
           (reduce-kv (fn [remaining deadline entries]
                        (let [kept (vec (remove #(= owner (get-in % [:frame :owner])) entries))]
                          (if (seq kept)
                            (assoc remaining deadline kept)
                            (dissoc remaining deadline))))
                      (empty buckets)
                      buckets)))
  {:status :aborted :owner owner})

(defonce ^:private production-runtime* (atom nil))

(defn install-production!
  "Install the one server-side final runtime instance used by AC's
   composition root.  The caller supplies neutral state callbacks; this
   function owns no Minecraft objects and is safe to invoke once at startup."
  [{:keys [state-provider commit-state! ability-state-provider commit-ability-state! remove-ability-state!]}]
  (let [runtime (create-from-capabilities {:state-provider state-provider
                                           :commit-state! commit-state!
                                           :ability-state-provider ability-state-provider
                                           :commit-ability-state! commit-ability-state!
                                           :remove-ability-state! remove-ability-state!})]
    (initialize! runtime)
    (reset! production-runtime* runtime)
    runtime))

(defn production-runtime [] @production-runtime*)

(defn- frame-world-id
  "Resolve the immutable world snapshot used by the final execution frame."
  [intent]
  (or (:world-id intent)
      (get-in intent [:context :world-id])
      (get-in intent [:capabilities :world/id])
      "minecraft:overworld"))
(defn dispatch-production! [owner ability-id intent]
  (if-let [runtime @production-runtime*]
    (dispatch! runtime ability-id
               {:owner owner
                :ability-id ability-id
                :world (frame-world-id intent)
                :tick (long (or (:server-tick intent) (:tick intent) 0))
                :seed (long (or (:activation-seed intent)
                                (hash [owner ability-id (:server-tick intent)])))
                :input (dissoc intent :owner :ability-id)})
    {:status :rejected :reason :final-runtime-not-installed}))


