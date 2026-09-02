(ns cn.li.ability.engine
  "Content-module-neutral composition root for the final authoritative
   combat engine. Ported from ac's final_runtime.clj -- that namespace was
   already Minecraft-free and already only touched combat-core's public
   API, except for one seam: catalog assembly (:initialize-catalog/
   :catalog-status/:registration) came straight from AC's
   final-catalog-service. Those three collapse into a single injected
   :catalog-compile function here (returns the assembled
   {:node-environment :combat :vfx ...} map, exactly the shape
   final-catalog-service/initialize! already produced) -- catalog-status
   and registration are then plain reads off this runtime's own :catalog
   atom, which already holds everything the assembled map carries, so no
   second injected function is needed for either. A future BC/CC pack
   supplies its own :catalog-compile; this namespace never requires
   cn.li.ac.* or knows AC's EDN layout exists."
  (:require [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.mcmod.runtime.host :as host]))

(defn create-runtime
  [{:keys [host state-provider commit-state! ability-state-provider commit-ability-state!
           catalog-compile remove-ability-state!] :as options}]
  (when-not (map? host) (throw (ex-info "final runtime requires neutral host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final runtime requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final runtime requires commit-state!" {})))
  (when-not (ifn? catalog-compile) (throw (ex-info "final runtime requires catalog-compile" {})))
  {:options options
   :catalog-compile catalog-compile
   :remove-ability-state! remove-ability-state!
   :engine (combat-api/create-engine {:host host
                                      :state-provider state-provider
                                      :commit-state! commit-state!
                                      :ability-state-provider ability-state-provider
                                      :commit-ability-state! commit-ability-state!})
   :catalog (atom nil)
   :scheduled (atom (sorted-map))})

(defn create-from-capabilities
  "Build the final host from mcmod's neutral capability snapshot.

   Query handlers receive plain request maps.  Action handlers are wrapped so
   the final host can preflight every command without invoking a mutating
   Minecraft operation; only the apply phase crosses the mcmod boundary."
  [{:keys [state-provider commit-state! ability-state-provider commit-ability-state!
           catalog-compile remove-ability-state!] :as options}]
  (let [snapshot (capabilities/snapshot)
        host-instance (host/create
                       {:queries (:queries snapshot)
                        :actions (into {}
                                       (map (fn [[capability handler]]
                                              [capability
                                               (fn [phase command context]
                                                 (if (= :preflight phase)
                                                   true
                                                   (handler (merge (:args command)
                                                                   {:owner (:owner command)
                                                                    :world-id (:world-id command)
                                                                    :ability-id (:ability-id command)}))))]))
                                       (:actions snapshot))})]
    (create-runtime {:host host-instance
                     :state-provider state-provider
                      :commit-state! commit-state!
                      :ability-state-provider ability-state-provider
                      :commit-ability-state! commit-ability-state!
                      :catalog-compile catalog-compile
                      :remove-ability-state! remove-ability-state!})))

(defn initialize! [runtime]
  (let [assembled ((:catalog-compile runtime))]
    (reset! (:catalog runtime)
            (assoc assembled
                   :damage-policies
                   (vec (mapcat (fn [[ability-id source]]
                                  (map #(assoc % :ability-id ability-id)
                                       (:damage-policies source)))
                                (get-in assembled [:combat :sources])))))
    runtime))

(defn catalog-status [runtime]
  (if @(:catalog runtime)
    (select-keys @(:catalog runtime) [:status :content-hash])
    {:status :cold}))

(defn resolve-damage!
  "Resolve a neutral damage event through the final damage policy engine."
  [runtime raw-event]
  (let [policies (:damage-policies @(:catalog runtime))]
    (assoc (combat-api/resolve-damage policies raw-event) :status :accepted)))

(defn- registration [runtime ability-id]
  (get-in @(:catalog runtime) [:combat :by-id ability-id]))

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
        (let [result (assoc (combat-api/execute! (:engine runtime) (:compiled entry) frame)
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
        {:status :accepted
         :tick tick
         :results (mapv (fn [{:keys [program frame]}]
                          (combat-api/execute! (:engine runtime) program (assoc frame :tick tick)))
                        due)}))))

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
  "Install the one server-side final runtime instance used by a content
   pack's composition root.  The caller supplies neutral state callbacks and
   its own catalog-compile; this function owns no Minecraft objects and is
   safe to invoke once at startup."
  [{:keys [state-provider commit-state! ability-state-provider commit-ability-state!
           catalog-compile remove-ability-state!]}]
  (let [runtime (create-from-capabilities {:state-provider state-provider
                                           :commit-state! commit-state!
                                           :ability-state-provider ability-state-provider
                                           :commit-ability-state! commit-ability-state!
                                           :catalog-compile catalog-compile
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
