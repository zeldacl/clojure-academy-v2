(ns cn.li.ac.content.ability.vecmanip.vec-reflection
  "VecReflection skill - advanced reflection (toggle).

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.content.ability.vecmanip.arbitration :as arbitration]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as fx-common]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private vec-reflection-skill-id :vec-reflection)

(defn default-reflection-runtime-state
  []
  {:reflecting-pairs #{}
   :reflection-depths {}})

(defn create-vec-reflection-runtime
  ([]
   (create-vec-reflection-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-reflection-runtime-state))}}]
   {::runtime ::vec-reflection-runtime
    :state* state*}))

(def ^:dynamic *vec-reflection-runtime* nil)

(defn- vec-reflection-runtime?
  [runtime]
  (and (map? runtime)
       (= ::vec-reflection-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-vec-reflection-runtime
  [runtime f]
  (when-not (vec-reflection-runtime? runtime)
    (throw (ex-info "Expected VecReflection runtime"
                    {:value runtime})))
  (binding [*vec-reflection-runtime* runtime]
    (f)))

(defmacro with-vec-reflection-runtime
  [runtime & body]
  `(call-with-vec-reflection-runtime ~runtime (fn [] ~@body)))

(defn install-vec-reflection-runtime!
  ([]
   (install-vec-reflection-runtime! (create-vec-reflection-runtime)))
  ([runtime]
   (when-not (vec-reflection-runtime? runtime)
     (throw (ex-info "Expected VecReflection runtime"
                     {:value runtime})))
   (alter-var-root #'*vec-reflection-runtime* (constantly runtime))
   nil))

(defn clear-vec-reflection-runtime!
  []
  (alter-var-root #'*vec-reflection-runtime* (constantly nil))
  nil)

(defn- current-vec-reflection-runtime
  []
  *vec-reflection-runtime*)

(defn- require-vec-reflection-runtime
  []
  (or (current-vec-reflection-runtime)
      (throw (ex-info "VecReflection runtime is not bound"
                      {:required 'vec-reflection-runtime}))))

(defn- reflection-runtime-state-atom
  []
  (:state* (require-vec-reflection-runtime)))

(defn- reflection-runtime-state-snapshot
  []
  @(reflection-runtime-state-atom))

(defn- update-reflection-runtime!
  [f & args]
  (apply swap! (reflection-runtime-state-atom) f args))

(def ^:dynamic *reflection-chain-id* nil)

(defn- current-reflection-chain-id
  []
  *reflection-chain-id*)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double vec-reflection-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double vec-reflection-skill-id field-id (exp01 exp)))

(defn- cfg-string-set [field-id]
  (set (skill-config/tunable-string-list vec-reflection-skill-id field-id)))

(defn- cfg-int [field-id]
  (skill-config/tunable-int vec-reflection-skill-id field-id))

(defn- parse-difficulty-entry [entry]
  (try
    (let [entry* (str entry)
          idx (.lastIndexOf ^String entry* ":")]
      (when (pos? idx)
        [(subs entry* 0 idx)
         (Double/parseDouble (subs entry* (inc idx)))]))
    (catch Exception _
      nil)))

(defn- affected-entity-difficulty []
  (into {}
        (keep parse-difficulty-entry)
        (skill-config/tunable-string-list vec-reflection-skill-id :targeting.affected-entity-difficulty)))

(defn- excluded-entity-ids []
  (cfg-string-set :targeting.excluded-entity-ids))

(defn- large-fireball-ids []
  (cfg-string-set :targeting.large-fireball-ids))

(defn- small-fireball-ids []
  (cfg-string-set :targeting.small-fireball-ids))

(defn- fireball-entity?
  [entity-id]
  (or (contains? (large-fireball-ids) entity-id)
      (contains? (small-fireball-ids) entity-id)))

(defn- skill-exp [player-id]
  (fx-common/skill-exp player-id vec-reflection-skill-id))

(defn- current-cp
  [player-id]
  (fx-common/current-cp player-id))

(defn- consume-cp!
  [player-id cp]
  (boolean (:success? (fx-common/perform-resource! player-id 0.0 (double cp) false))))

(defn- enforce-overload-floor!
  [player-id floor-value]
  (fx-common/enforce-overload-floor! player-id floor-value))

(defn vec-reflection-cost-tick-cp
  [{:keys [player-id]}]
  (cfg-lerp :cost.tick.cp (skill-exp player-id)))

(defn- get-player-position [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn- entity-registry-id [entity]
  (or (:entity-id entity) (:type entity) ""))

(defn- affect-difficulty-with-snapshot [entity excluded-ids difficulty-map]
  (let [eid (entity-registry-id entity)]
    (when-not (or (contains? excluded-ids eid)
                  (:item? entity)
                  (:living? entity)
                  (:mob? entity))
      (double (get difficulty-map eid 1.0)))))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- visited-ttl-ms []
  (* 50 (long (max 1 (cfg-int :tracking.visited-ttl-ticks)))))

(defn- visited-max-size []
  (int (max 16 (cfg-int :tracking.visited-max-size))))

(defn- max-reflections []
  (int (max 1 (cfg-int :combat.max-reflections))))

(defn- pair-key [player-id attacker-id]
  (let [a (str (or player-id ""))
        b (str (or attacker-id ""))]
    (if (neg? (compare a b))
      [a b]
      [b a])))

(defn- new-reflection-chain-id []
  (str (java.util.UUID/randomUUID)))

(defn reflection-owner-key
  "Return the owner key used for VecReflection recursion state.
  Public for diagnostics/tests; gameplay code should call reflect-damage."
  [player-id attacker-id ctx-id chain-id]
  {:ctx-id (or ctx-id :no-context)
   :player-id (str (or player-id ""))
   :pair (pair-key player-id attacker-id)
   :chain-id (or chain-id :no-chain)})

(defn- try-enter-reflection!
  [owner-key]
  (let [entered? (volatile! false)]
    (update-reflection-runtime!
      (fn [{:keys [reflecting-pairs] :as state}]
        (if (contains? reflecting-pairs owner-key)
          state
          (do
            (vreset! entered? true)
            (update state :reflecting-pairs conj owner-key)))))
    @entered?))

(defn- leave-reflection!
  [owner-key]
  (update-reflection-runtime!
    (fn [{:keys [reflection-depths] :as state}]
      (let [next-depths (if-let [v (get reflection-depths owner-key)]
                          (let [nv (dec (long v))]
                            (if (pos? nv)
                              (assoc reflection-depths owner-key nv)
                              (dissoc reflection-depths owner-key)))
                          reflection-depths)]
        (-> state
            (update :reflecting-pairs disj owner-key)
            (assoc :reflection-depths next-depths)))))
  nil)

(defn reset-reflection-runtime-for-test!
  []
  (reset! (reflection-runtime-state-atom) (default-reflection-runtime-state))
  nil)

(defn reflection-runtime-snapshot
  []
  (reflection-runtime-state-snapshot))

(defn mark-reflecting-for-test!
  [player-id attacker-id ctx-id chain-id]
  (let [owner-key (reflection-owner-key player-id attacker-id ctx-id chain-id)]
    (update-reflection-runtime! update :reflecting-pairs conj owner-key)
    owner-key))

(defn set-reflection-depth-for-test!
  [player-id attacker-id ctx-id chain-id depth]
  (let [owner-key (reflection-owner-key player-id attacker-id ctx-id chain-id)]
    (update-reflection-runtime! assoc-in [:reflection-depths owner-key] (long depth))
    owner-key))

(defn- normalize-visited-map [visited now]
  (cond
    (map? visited)
    (into {}
          (keep (fn [[k v]]
                  (when k
                    [(str k) (long (if (number? v) v now))])))
          visited)

    (set? visited)
    (into {}
          (map (fn [uuid] [(str uuid) now]))
          visited)

    :else
    {}))

(defn- prune-visited-map [visited now ttl-ms max-size]
  (let [cutoff (- now (long ttl-ms))
        alive (into {}
                    (filter (fn [[_uuid ts]]
                              (>= (long ts) cutoff)))
                    visited)]
    (if (<= (count alive) max-size)
      alive
      (->> alive
           (sort-by (fn [[_uuid ts]] (long ts)) >)
           (take max-size)
           (into {})))))

(defn- active-vec-reflection-ctx-id
  [player-id]
  (->> (ctx/get-all-contexts)
       (filter (fn [[_ctx-id ctx-data]]
                 (and (= (:player-uuid ctx-data) player-id)
                      (toggle/is-toggle-active? ctx-data :vec-reflection))))
       first
       first))

(defn- add-exp! [player-id amount]
  (fx-common/add-skill-exp! player-id vec-reflection-skill-id amount))

(defn- send-fx-reflect-entity! [ctx-id entity]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-reflect-entity
                           {:mode :reflect-entity
                            :x (double (or (:x entity) 0.0))
                            :y (double (+ (double (or (:y entity) 0.0))
                                          (* 0.6 (double (or (:eye-height entity)
                                                             (:height entity)
                                                             0.0)))))
                            :z (double (or (:z entity) 0.0))
                            :reflected? true}))

(defn- send-fx-play! [ctx-id pos]
  (ctx/ctx-send-to-client! ctx-id :vec-reflection/fx-play
                           {:mode :play
                            :x (double (or (:x pos) 0.0))
                            :y (double (or (:y pos) 0.0))
                            :z (double (or (:z pos) 0.0))}))

(defn- try-find-attacker-pos [player-id attacker-id]
  (or (when-let [st (fx-common/get-player-state attacker-id)]
        (get st :position))
      (when-let [self-pos (get-player-position player-id)]
        (when world-effects/*world-effects*
          (first (filter (fn [ent] (= (:uuid ent) attacker-id))
                         (world-effects/find-entities-in-radius
                          world-effects/*world-effects*
                          (:world-id self-pos)
                          (:x self-pos)
                          (:y self-pos)
                          (:z self-pos)
                          (cfg-double :targeting.attacker-search-radius))))))))

(defn vec-reflection-on-key-down
  "Activate or deactivate toggle skill."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [is-active? (toggle/is-toggle-active? ctx-data :vec-reflection)
            exp (skill-exp player-id)]
        (if is-active?
          (do
            (toggle/remove-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited :vec-reflection-visited-map)
            (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
            (fx/send-end! ctx-id :vec-reflection/fx-end)
            (log/info "VecReflection: Deactivated"))
          (do
            (toggle/activate-toggle! ctx-id :vec-reflection)
            (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-visited-map] {})
            (let [overload-keep (cfg-lerp :cost.overload-keep exp)]
              (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-overload-keep] overload-keep)
              (enforce-overload-floor! player-id overload-keep))
            (fx/send-start! ctx-id :vec-reflection/fx-start)
            (log/info "VecReflection: Activated")))))
    (catch Exception e
      (log/warn "VecReflection key-down failed:" (ex-message e)))))

(defn- vec-reflection-on-key-tick-body
  [player-id ctx-id cost-ok?]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (when (toggle/is-toggle-active? ctx-data :vec-reflection)
      (let [exp (skill-exp player-id)
            overload-keep (get-in ctx-data [:skill-state :vec-reflection-overload-keep] 0.0)]
        (toggle/update-toggle-tick! ctx-id :vec-reflection)
        (enforce-overload-floor! player-id overload-keep)

        (when-not cost-ok?
          (toggle/deactivate-toggle! ctx-id :vec-reflection)
          (fx/send-end! ctx-id :vec-reflection/fx-end)
          (log/info "VecReflection: Deactivated (insufficient CP)"))

        (when (and cost-ok?
                   (toggle/is-toggle-active? (or (ctx/get-context ctx-id) ctx-data) :vec-reflection))
          (when-let [pos (get-player-position player-id)]
            (when world-effects/*world-effects*
              (let [world-id (:world-id pos)
                    x (:x pos)
                    y (:y pos)
                    z (:z pos)
                    now (now-ms)
                    ttl-ms (visited-ttl-ms)
                    max-size (visited-max-size)
                    excluded-ids (excluded-entity-ids)
                    difficulty-map (affected-entity-difficulty)
                    dual-active? (arbitration/dual-active? player-id)
                    arbitration-allowed? (or (not dual-active?)
                                             (arbitration/skill-allowed-in-dual-active? :vec-reflection))
                    entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                    world-id x y z (cfg-double :targeting.radius))
                    visited (normalize-visited-map
                             (or (get-in ctx-data [:skill-state :vec-reflection-visited-map])
                                 (get-in ctx-data [:skill-state :vec-reflection-visited]))
                             now)
                    fresh-entities (remove (fn [entity]
                                             (contains? visited (str (:uuid entity))))
                                           entities)]
                (doseq [entity fresh-entities]
                  (let [entity-id (:uuid entity)
                        eid (entity-registry-id entity)
                        difficulty (affect-difficulty-with-snapshot entity excluded-ids difficulty-map)]
                    (when (and entity-id (not= entity-id player-id) difficulty)
                      (when-let [look-vec (and raycast/*raycast*
                                               (raycast/get-player-look-vector raycast/*raycast* player-id))]
                        (when (and arbitration-allowed?
                                   (arbitration/claim-projectile! player-id :vec-reflection entity-id))
                          (let [entity-vel (when entity-motion/*entity-motion*
                                             (entity-motion/get-velocity entity-motion/*entity-motion*
                                                                         world-id
                                                                         entity-id))
                                speed (Math/sqrt (+ (Math/pow (double (or (:x entity-vel) 0.0)) 2.0)
                                                    (Math/pow (double (or (:y entity-vel) 0.0)) 2.0)
                                                    (Math/pow (double (or (:z entity-vel) 0.0)) 2.0)))
                                vel-x (* (double (:x look-vec)) speed)
                                vel-y (* (double (:y look-vec)) speed)
                                vel-z (* (double (:z look-vec)) speed)
                                reflect-cost (* difficulty (cfg-lerp :cost.reflect-entity.cp exp))]
                            (if-not (consume-cp! player-id reflect-cost)
                              (do
                                (toggle/deactivate-toggle! ctx-id :vec-reflection)
                                (fx/send-end! ctx-id :vec-reflection/fx-end)
                                (log/info "VecReflection: Deactivated (insufficient reflect CP)"))
                              (do
                                (if (and world-effects/*world-effects*
                                         (fireball-entity? eid))
                                  (let [spawn-result (world-effects/spawn-projectile! world-effects/*world-effects*
                                                                                    world-id
                                                                                    {:entity-id eid
                                                                                     :x (double (or (:x entity) 0.0))
                                                                                     :y (double (or (:y entity) 0.0))
                                                                                     :z (double (or (:z entity) 0.0))
                                                                                     :vx vel-x
                                                                                     :vy vel-y
                                                                                     :vz vel-z
                                                                                     :owner-uuid player-id})
                                        spawned? (boolean (:success? spawn-result))]
                                    (when (and spawned? entity-motion/*entity-motion*)
                                      (entity-motion/discard-entity! entity-motion/*entity-motion* world-id entity-id))
                                    (when (and (not spawned?) entity-motion/*entity-motion*)
                                      (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                                   world-id
                                                                   entity-id vel-x vel-y vel-z)))
                                  (when entity-motion/*entity-motion*
                                    (entity-motion/set-velocity! entity-motion/*entity-motion*
                                                                 world-id
                                                                 entity-id vel-x vel-y vel-z)))
                                (add-exp! player-id (* difficulty (cfg-double :progression.exp-reflect-entity-scale)))
                                (send-fx-reflect-entity! ctx-id entity)
                                (log/debug "VecReflection: Reflected entity" entity-id)))))))))
                (let [visited-with-current (reduce (fn [acc entity]
                                                     (if-let [uuid (:uuid entity)]
                                                       (assoc acc (str uuid) now)
                                                       acc))
                                                   visited
                                                   entities)
                      pruned (prune-visited-map visited-with-current now ttl-ms max-size)]
                  (ctx/update-context! ctx-id assoc-in [:skill-state :vec-reflection-visited-map] pruned)
                  ;; keep old key cleaned to avoid stale accumulation after migration
                  (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited))))))))))

(defn vec-reflection-on-key-tick
  "Tick handler - consume resources and reflect nearby projectiles."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (vec-reflection-on-key-tick-body player-id ctx-id cost-ok?)
    (catch Exception e
      (log/warn "VecReflection key-tick failed:" (ex-message e)))))

(defn vec-reflection-on-key-up
  "No-op for toggle skills."
  [{:keys [_player-id _ctx-id]}]
  nil)

(defn vec-reflection-on-key-abort
  "Deactivate on abort."
  [{:keys [ctx-id]}]
  (try
    (toggle/remove-toggle! ctx-id :vec-reflection)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-visited :vec-reflection-visited-map)
    (ctx/update-context! ctx-id update-in [:skill-state] dissoc :vec-reflection-overload-keep)
    (fx/send-end! ctx-id :vec-reflection/fx-end)
    (log/debug "VecReflection aborted")
    (catch Exception e
      (log/warn "VecReflection key-abort failed:" (ex-message e)))))

(defn reflect-damage
  "Reflect incoming damage back to attacker when VecReflection is active.
  Returns tuple [performed? reduced-damage]."
  [player-id attacker-id original-damage]
  (try
    (let [chain-id (or (current-reflection-chain-id) (new-reflection-chain-id))]
      (binding [*reflection-chain-id* chain-id]
        (let [ctx-id (active-vec-reflection-ctx-id player-id)
              owner-key (reflection-owner-key player-id attacker-id ctx-id chain-id)]
          (if-not (try-enter-reflection! owner-key)
            [false original-damage]
            (try
              (if-let [state (fx-common/get-player-state player-id)]
                (let [depth-state (update-reflection-runtime! update-in [:reflection-depths owner-key] (fnil inc 0))
                      depth (max 0 (dec (get-in depth-state [:reflection-depths owner-key] 1)))
                      exp (skill-exp player-id)
                      max-depth (max-reflections)
                      reflect-multiplier (* (cfg-lerp :combat.damage-multiplier exp)
                                            (Math/pow 0.5 (double depth)))
                      reflected-damage (* original-damage reflect-multiplier)
                      consumption (* original-damage (cfg-lerp :cost.damage.cp exp))
                      current-cp (current-cp player-id)]
                  (if (and (< depth max-depth)
                           (>= current-cp consumption)
                           (>= reflected-damage (cfg-double :combat.min-reflected-damage)))
                    (do
                      (consume-cp! player-id consumption)
                      (when (and attacker-id entity-damage/*entity-damage*)
                        (let [world-id (or (get-in state [:position :world-id])
                                           (fx-common/player-path attacker-id [:position :world-id])
                                           "minecraft:overworld")]
                          (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                              world-id
                                                              attacker-id
                                                              reflected-damage
                                                              :generic)))
                      (add-exp! player-id (* original-damage (cfg-double :progression.exp-damage-scale)))
                      (when ctx-id
                        (when-let [attacker-pos (and attacker-id (try-find-attacker-pos player-id attacker-id))]
                          (send-fx-play! ctx-id attacker-pos)))
                      [true (max 0.0 (- original-damage reflected-damage))])
                    [false original-damage]))
                [false original-damage])
              (finally
                (leave-reflection! owner-key)))))))
    (catch Exception e
      (log/warn "VecReflection reflect-damage failed:" (ex-message e))
      [false original-damage])))

(defn can-cancel-attack?
  "Pure precheck for Attack-stage cancel semantics.
  Mirrors original passby gate: only cancels when reflection can actually perform."
  [player-id _attacker-id original-damage]
  (try
    (if (fx-common/get-player-state player-id)
      (let [ctx-id (active-vec-reflection-ctx-id player-id)
            exp (skill-exp player-id)
            consumption (* original-damage (cfg-lerp :cost.damage.cp exp))
            reflected-damage (* original-damage (cfg-lerp :combat.damage-multiplier exp))
            current-cp (current-cp player-id)]
        (and ctx-id
             (>= current-cp consumption)
             (>= reflected-damage (cfg-double :combat.min-reflected-damage))))
      false)
    (catch Exception e
      (log/warn "VecReflection can-cancel-attack failed:" (ex-message e))
      false)))

(defn- on-precheck-cancel-side-effect!
  "Run reflection side-effects during precheck cancel path so platforms
  without mutable hurt-stage hooks still execute reflection behavior."
  [player-id attacker-id original-damage _damage-source]
  (when (can-cancel-attack? player-id attacker-id original-damage)
    (reflect-damage player-id attacker-id original-damage)
    true))

(declare vec-reflection)

(defskill vec-reflection
  :id :vec-reflection
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_reflection"
  :description-key "ability.skill.vecmanip.vec_reflection.desc"
  :icon "textures/abilities/vecmanip/skills/vec_reflection.png"
  :ui-position [210 50]
  :level 4
  :controllable? true
  :ctrl-id :vec-reflection
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :pattern :release-cast
  :cost {:tick {:cp vec-reflection-cost-tick-cp}}
  :actions {:down! vec-reflection-on-key-down
            :tick! vec-reflection-on-key-tick
            :up! vec-reflection-on-key-up
            :abort! vec-reflection-on-key-abort}
  :prerequisites [{:skill-id :vec-deviation :min-exp 0.0}])

(defn init!
  []
  (damage-handler/register-toggle-damage-handler!
    :vec-reflection-damage
    :vec-reflection
    (fn [player-id attacker-id damage _damage-source]
      (let [[_performed reduced-damage] (reflect-damage player-id attacker-id damage)]
        [reduced-damage {:handler :vec-reflection}]))
    60)
  (damage-handler/register-attack-cancel-check!
    :vec-reflection
    can-cancel-attack?)
  (damage-handler/register-attack-precheck-side-effect!
    :vec-reflection
    on-precheck-cancel-side-effect!)
  nil)
