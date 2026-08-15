(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as combat]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.ac.ability.service.radiation-marks :as radiation-marks]
            [cn.li.ac.ability.service.light-shield-state :as light-shield-state]
            [cn.li.ac.ability.util.attack :as attack]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.runtime.combat-contract :as contract]))

(defonce ^:private engine* (atom nil))
(defonce ^:private catalog* (atom nil))
(defonce ^:private world-effect-handler* (atom nil))
(defonce ^:private result-sink* (atom nil))
(declare owner-state resolve-slot)

(defn apply-combat-domain-event
  "Apply AC-owned combat domain transitions without platform or Context state.

   Combat Core owns the domain-state atom; this function owns only the
   immutable content semantics for radiation marks and Light Shield state.
   World mutation and network/VFX delivery remain outside this reducer."
  [state event]
  (case (:type event)
    :radiation-mark
    (let [target (str (:target-id event))
          marks (or (:radiation-marks state) {})]
      (assoc state :radiation-marks
             (assoc marks target
                    (radiation-marks/mark (get marks target) event))))

    :combat-tick
    (update state :radiation-marks
            #(radiation-marks/tick (or %) (:tick event)))

    :combat-owner-clear
    (update state :radiation-marks
            #(radiation-marks/clear-owner (or %) (:owner event)))

    :light-shield-start
    (assoc-in state [:light-shields (str (:owner event))]
              (light-shield-state/start (:overload-floor event)))

    :light-shield-tick
    (update-in state [:light-shields (str (:owner event))]
               light-shield-state/tick)

    :light-shield-end
    (update state :light-shields dissoc (str (:owner event)))

    state))

(defn- vec-accel-query
  "Resolve the source VecAccel release query from neutral raycast ports.

   The calculation is intentionally kept at the AC composition boundary: the
   engine receives only an immutable launch plan, while platform code applies
   the resulting velocity.  Constants and interpolation match the authoritative
   VecAccel content configuration (20-tick charge, sine speed curve and the
   -0.174533 radian pitch offset)."
  [context node]
  (let [owner (str (:owner context))
        max-charge (max 1 (long (or (:max-charge-ticks node) 20)))
        charge-ticks (-> (get-in context [:session-state :charge-ticks] 0.0)
                         double Math/round long (max 0) (min max-charge))
        exp (double (ability-model/get-skill-exp
                     (get-in context [:state :ability-data]) :vec-accel))
        look (raycast/player-look-vector owner)
        position (raycast/player-position owner)
        ground? (when (and position (raycast/available?))
                  (some? (raycast/raycast-blocks
                          (or (:world-id position) "minecraft:overworld")
                          (double (:x position)) (double (:y position)) (double (:z position))
                          0.0 -1.0 0.0
                          (skill-config/tunable-double :vec-accel
                                                        :targeting.ground-check-distance))))
        can-perform? (or (> exp (skill-config/tunable-double
                                 :vec-accel :targeting.groundless-exp-threshold))
                         ground?)]
    (when (and (map? look) can-perform?)
      (let [lx (double (:x look)) ly (double (:y look)) lz (double (:z look))
            horizontal (Math/sqrt (+ (* lx lx) (* lz lz)))
            safe-horizontal (if (pos? horizontal) horizontal 1.0)
            pitch (Math/atan2 (- ly) safe-horizontal)
            pitch (+ pitch (skill-config/tunable-double
                            :vec-accel :movement.pitch-offset-radians))
            progress (max 0.0 (min 1.0 (/ (double charge-ticks) (double max-charge))))
            speed-progress (skill-config/lerp-double :vec-accel
                                                       :movement.speed-progress progress)
            speed (* (Math/sin speed-progress)
                     (skill-config/tunable-double :vec-accel :movement.max-velocity))
            cos-p (Math/cos pitch)
            sin-p (Math/sin pitch)]
        {:charge-ticks charge-ticks
         :can-perform? true
         :initial-velocity {:x (* cos-p (/ lx safe-horizontal) speed)
                            :y (- (* sin-p speed))
                            :z (* cos-p (/ lz safe-horizontal) speed)}}))))

(defn- vec-deviation-query
  "Resolve the authoritative projectile scan for one Combat pulse.

   The result is immutable neutral data.  Platform adapters decide how an
   entity is stopped; no entity object or Context state crosses this boundary.
   Already-tagged entities and the owner are excluded so repeated deadline
   pulses do not reapply the same deflection." 
  [context node]
  (let [owner (str (:owner context))
        position (raycast/player-position owner)
        radius (double (or (:radius node)
                           (skill-config/tunable-double :vec-deviation
                                                        :targeting.radius)))]
    (when (and (map? position)
               (world-effects/available?)
               (Double/isFinite radius)
               (<= 0.0 radius 32.0))
      (let [entities (world-effects/find-entities-in-radius
                      (:world-id position)
                      (double (:x position))
                      (double (:y position))
                      (double (:z position)) radius)]
        {:center (select-keys position [:x :y :z :world-id])
         :radius radius
         :entities (->> (or entities [])
                         (filter map?)
                         (remove #(= owner (str (or (:uuid %) (:entity-id %)))))
                         (remove :vec-deviation-marked?)
                         (remove :ac-vm-deviated?)
                         (take 64)
                         vec)}))))

(defn- academy-damage-pipeline
  "Pure AC-owned damage transforms contributed by passive Combat abilities.

   The transform only reads the immutable owner snapshot supplied to Combat
   Core.  It never reaches the player store or installs a platform damage
   listener, so passive skills remain part of the deterministic pipeline." 
  []
  [{:priority 100
    :provider-id :academy/base
    :ability-id :rad-intensify
    :node-id :damage-amplifier
    :run (fn [request context]
           (let [exp (double (ability-model/get-skill-exp
                              (get-in (or (:source-state context)
                                           (:state context) {}) [:ability-data])
                              :rad-intensify))
                 multiplier (+ 1.4 (* 0.4 exp))]
             (update request :base #(* (double %) multiplier))))}
   {:priority 50
    :provider-id :academy/base
    :ability-id :vec-deviation
    :node-id :damage-reduction
   :run (fn [request context]
           (let [active? (contains? (get-in (or (:target-state context)
                                               (:state context) {}) [:active-abilities] #{})
                                    :vec-deviation)
                 base (double (:base request))
                 exp (double (ability-model/get-skill-exp
                              (get-in (or (:target-state context)
                                           (:state context) {}) [:ability-data])
                              :vec-deviation))
                 reduction-rate (+ 0.4 (* 0.5 exp))
                 cp-cost (max 0.0 (+ 15.0 (* -3.0 exp)))]
             (if (and active?
                      (Double/isFinite base)
                      (<= 0.0 base 9999.0))
               (-> request
                   (assoc :base (* base (- 1.0 reduction-rate)))
                   (assoc-in [:metadata :resource-cost] {:cp (- cp-cost)})
                   (assoc-in [:metadata :vec-deviation]
                             {:reduction-rate reduction-rate
                              :damage-ignore-threshold 9999.0}))
               request)))}])

(defn initialize!
  ([] (initialize! {}))
  ([{:keys [owner-state-fn query-port now-tick ability-resolver damage-pipeline
            domain-event-handler]}]
   (or @engine*
       (let [catalog (compiler/compile-all!)
             default-query-port
             {:raycast (fn [context node]
                         (if-let [host-query (contract/host-port :query)]
                           (host-query :raycast context node)
                           (when (raycast/available?)
                             (let [owner (:owner context)
                                   hit (raycast/raycast-from-player
                                        owner
                                        (double (or (:distance node) 12.0))
                                        true)
                                   position (raycast/player-position owner)]
                               (cond-> hit
                                 (and (map? position) (:world-id position))
                                 (assoc :world-id (:world-id position)))))))
              :entities (fn [context node]
                          (when-let [host-query (contract/host-port :query)]
                            (host-query :entities context node)))
              :charge-target (fn [context node]
                               (when-let [host-query (contract/host-port :query)]
                                 (host-query :charge-target context node)))
              :block-scan (fn [context node]
                            (when-let [host-query (contract/host-port :query)]
                              (host-query :block-scan context node)))
              :attack (fn [context node]
                        (if-let [host-query (contract/host-port :query)]
                          (host-query :attack context node)
                          (let [owner (:owner context)
                                range (double (or (:range node) 20.0))
                                attack-data (attack/resolve-attack-data owner range)
                                excluded (cond-> #{owner}
                                           (:target-uuid attack-data)
                                           (conj (:target-uuid attack-data)))
                                victims (attack/aoe-victims
                                         (:world-id attack-data)
                                         (:impact attack-data)
                                         (double (or (:aoe-radius node) 8.0))
                                         excluded)]
                            (assoc attack-data :victims victims))))
              :ray-barrage (fn [context node]
                             (if-let [host-query (contract/host-port :query)]
                               (host-query :ray-barrage context node)
                               (let [owner (:owner context)
                                     range (double (or (:range node) 20.0))
                                     attack-data (attack/resolve-attack-data owner range)
                                     victims (attack/aoe-victims
                                              (:world-id attack-data)
                                              (:impact attack-data)
                                              10.0
                                              #{owner})]
                                 (assoc attack-data :victims victims))))
              :directed-blastwave (fn [context node]
                                    (if-let [host-query (contract/host-port :query)]
                                      (host-query :directed-blastwave context node)
                                      (let [owner (:owner context)
                                            range (double (or (:range node) 4.0))
                                            attack-data (attack/resolve-attack-data owner range)
                                            victims (attack/aoe-victims
                                                     (:world-id attack-data)
                                                     (:impact attack-data)
                                                     3.0
                                                     #{owner})]
                                        (assoc attack-data :victims victims))))
              :groundshock (fn [context node]
                             (if-let [host-query (contract/host-port :query)]
                               (host-query :groundshock context node)
                               (when-let [block-scan (get-in context [:queries :block-scan])]
                                  (block-scan context (assoc node :query-type :block-scan)))))
              :thunder-clap (fn [context node]
                              (if-let [host-query (contract/host-port :query)]
                                (host-query :thunder-clap context node)
                                (let [owner (:owner context)
                                      range (double (or (:range node) 40.0))
                                      attack-data (attack/resolve-attack-data owner range)
                                      victims (attack/aoe-victims
                                               (:world-id attack-data)
                                               (:impact attack-data)
                                               (double (or (:aoe-radius node) 15.0))
                                               #{owner})]
                                  (assoc attack-data :victims victims))))
              :blood-retrograde (fn [context node]
                                  (if-let [host-query (contract/host-port :query)]
                                    (host-query :blood-retrograde context node)
                                    ((get-in context [:queries :raycast])
                                     context (assoc node :query-type :raycast))))
              :electron-missile (fn [context node]
                                  (when-let [host-query (contract/host-port :query)]
                                    (host-query :electron-missile context node)))
              :scatter-bomb (fn [context node]
                              (when-let [host-query (contract/host-port :query)]
                                (host-query :scatter-bomb context node)))
              :saved-location (fn [context node]
                                (when-let [host-query (contract/host-port :query)]
                                  (host-query :saved-location context node)))
              :teleport-target (fn [context node]
                                 (when-let [host-query (contract/host-port :query)]
                                   (host-query :teleport-target context node)))
              :jet-engine (fn [context node]
                            (when-let [host-query (contract/host-port :query)]
                              (host-query :jet-engine context node)))
              :light-shield (fn [context node]
                              (when-let [host-query (contract/host-port :query)]
                                (host-query :light-shield context node)))
              :storm-wing (fn [context node]
                            (when-let [host-query (contract/host-port :query)]
                              (host-query :storm-wing context node)))
              :mag-manip (fn [context node]
                           (when-let [host-query (contract/host-port :query)]
                             (host-query :mag-manip context node)))
              :mag-movement (fn [context node]
                              (when-let [host-query (contract/host-port :query)]
                                (host-query :mag-movement context node)))
              :vec-accel (fn [context node]
                           (if-let [host-query (contract/host-port :query)]
                             (host-query :vec-accel context node)
                             (vec-accel-query context node)))
              :flashing (fn [context node]
                          (when-let [host-query (contract/host-port :query)]
                            (host-query :flashing context node)))
              :vec-deviation (fn [context node]
                               (if-let [host-query (contract/host-port :query)]
                                 (host-query :vec-deviation context node)
                                 (vec-deviation-query context node)))}]
         (when-not (registry/frozen?) (registry/freeze!))
         (reset! catalog* catalog)
         (reset! engine* (combat/create-engine
                           {:catalog catalog
                            :initial-owner-state (or owner-state-fn owner-state)
                            :query-port (merge default-query-port (or query-port {}))
                            :now-tick now-tick
                            :ability-resolver (or ability-resolver resolve-slot)
                            :domain-event-handler domain-event-handler
                            :damage-pipeline (or damage-pipeline
                                                 (academy-damage-pipeline))}))
         (when-not @world-effect-handler*
           (reset! world-effect-handler*
                   (fn [owner effect]
                     (if-let [handler (contract/host-port :world-effect)]
                       (handler owner effect)
                       (case (:type effect)
                         :damage
                         (let [{:keys [request]} effect
                               {:keys [world-id target base type source]} request]
                           {:status (if (and world-id target
                                              (entity-damage/available?)
                                              (entity-damage/apply-direct-damage!
                                               world-id target base type
                                               {:attacker-uuid source}))
                                        :applied :failed)
                            :effect effect})
                         :damage-aoe
                         (let [{:keys [world-id origin radius amount damage-type]} effect
                               {:keys [x y z]} origin]
                            {:status (if (and world-id origin
                                              (entity-damage/available?)
                                              (entity-damage/apply-aoe-damage!
                                               world-id x y z (double radius)
                                               (double amount) damage-type false))
                                        :applied :failed)
                            :effect effect})
                         :damage-targets
                         (let [{:keys [world-id targets amount damage-type source]} effect
                               amount (double amount)
                               target-ids (->> (or targets [])
                                               (map #(or (:uuid %)
                                                         (:entity-id %)
                                                         (:target-id %)
                                                         %))
                                               (filter string?)
                                               distinct
                                               sort
                                               (take 64))
                               hits (if (and world-id
                                              (Double/isFinite amount)
                                              (pos? amount)
                                              (entity-damage/available?))
                                      (reduce (fn [n target-id]
                                                (if (entity-damage/apply-direct-damage!
                                                     world-id target-id amount damage-type
                                                     {:attacker-uuid source})
                                                  (inc n)
                                                  n))
                                              0 target-ids)
                                      0)]
                           {:status (cond
                                      (= hits (count target-ids)) :applied
                                      (pos? hits) :partial
                                      :else :failed)
                            :hits hits
                            :target-count (count target-ids)
                            :effect effect})
                         :lightning
                         (let [{:keys [world-id origin visual-only?]} effect
                               {:keys [x y z]} (if (map? origin) origin {})
                               valid? (and world-id
                                            (every? #(and (number? %) (Double/isFinite (double %)))
                                                    [x y z])
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/spawn-lightning!
                                               world-id (double x) (double y) (double z)
                                               (boolean visual-only?)))
                                      :applied
                                      :failed)
                            :effect effect})
                         :spawn-projectile
                         (let [{:keys [world-id projectile-spec]} effect
                               spec (if (map? projectile-spec)
                                      (-> projectile-spec
                                          (update :delay-ticks #(max 0 (long (or % 0))))
                                          (update :damage #(when (number? %) (double %))))
                                      {})
                               valid? (and world-id
                                            (= :electron-bomb (:kind spec))
                                            (number? (:damage spec))
                                            (Double/isFinite (double (:damage spec)))
                                            (pos? (double (:damage spec)))
                                            (map? (:target spec))
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/spawn-projectile!
                                               world-id (assoc spec :owner owner)))
                                      :applied
                                      :failed)
                            :effect effect})
                         :ray-barrage
                         (let [{:keys [world-id query-result ray-count range
                                       cone-angle-degrees plain-damage scattered-damage
                                       special-target-policy]} effect
                               plan {:query-result query-result
                                     :ray-count (long (or ray-count 0))
                                     :range (double (or range 0.0))
                                     :cone-angle-degrees (double (or cone-angle-degrees 0.0))
                                     :plain-damage (double (or plain-damage 0.0))
                                     :scattered-damage (double (or scattered-damage 0.0))
                                     :special-target-policy special-target-policy}
                               valid? (and world-id
                                            (map? query-result)
                                            (<= 1 (:ray-count plan) 8)
                                            (pos? (:range plan))
                                            (<= 0.0 (:cone-angle-degrees plan) 360.0)
                                            (every? #(and (Double/isFinite %) (pos? %))
                                                    [(:plain-damage plan)
                                                     (:scattered-damage plan)])
                                            (= :silbarn special-target-policy)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-ray-barrage!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :directed-blastwave
                         (let [{:keys [world-id query-result ray-count aoe-radius
                                       amount damage-type movement breaking]} effect
                               {:keys [impulse knockback-y-adjust knockback-scale]} movement
                               {:keys [hardness-caps break-probability drop-probability]} breaking
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               bounded-probabilities? (and (vector? break-probability)
                                                           (= 2 (count break-probability))
                                                           (every? #(and (finite? %) (<= 0.0 (double %) 1.0))
                                                                   break-probability)
                                                           (vector? drop-probability)
                                                           (= 2 (count drop-probability))
                                                           (every? #(and (finite? %) (<= 0.0 (double %) 1.0))
                                                                   drop-probability))
                               valid? (and world-id (map? query-result)
                                            (<= 1 (long (or ray-count 1)) 16)
                                            (finite? aoe-radius) (pos? (double aoe-radius))
                                            (<= (double aoe-radius) 16.0)
                                            (finite? amount) (pos? (double amount))
                                            (<= (double amount) 1000.0)
                                            (every? #(and (finite? %) (<= -10.0 (double %) 10.0))
                                                    [impulse knockback-y-adjust knockback-scale])
                                            (vector? hardness-caps)
                                            (= 3 (count hardness-caps))
                                            (every? #(and (finite? %) (<= 0.0 (double %) 100.0))
                                                    hardness-caps)
                                            bounded-probabilities?
                                            (world-effects/available?))
                               plan (assoc effect :ray-count (long (or ray-count 1)))]
                           {:status (if (and valid?
                                              (world-effects/execute-directed-blastwave!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :groundshock
                         (let [{:keys [world-id query-result amount max-iterations
                                       init-energy entity-search-radius launch-scale
                                       launch-random-base launch-random-span breaking]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               {:keys [drop-rate ground-break-probability]} breaking
                               valid? (and world-id (map? query-result)
                                            (finite? amount) (pos? (double amount))
                                            (<= (double amount) 1000.0)
                                            (finite? max-iterations)
                                            (<= 1.0 (double max-iterations) 64.0)
                                            (finite? init-energy)
                                            (<= 0.0 (double init-energy) 1000.0)
                                            (finite? entity-search-radius)
                                            (<= 0.0 (double entity-search-radius) 16.0)
                                            (finite? launch-scale)
                                            (<= 0.0 (double launch-scale) 4.0)
                                            (finite? launch-random-base)
                                            (<= 0.0 (double launch-random-base) 4.0)
                                            (finite? launch-random-span)
                                            (<= 0.0 (double launch-random-span) 4.0)
                                            (vector? drop-rate)
                                            (= 2 (count drop-rate))
                                            (every? #(and (finite? %) (<= 0.0 (double %) 1.0)) drop-rate)
                                            (finite? ground-break-probability)
                                            (<= 0.0 (double ground-break-probability) 1.0)
                                            (world-effects/available?))
                               plan (assoc effect :max-iterations (long max-iterations))]
                           {:status (if (and valid?
                                              (world-effects/execute-groundshock!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :thunder-clap
                         (let [{:keys [world-id query-result amount aoe-radius
                                       charge-ticks cooldown-multiplier]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id (map? query-result)
                                            (finite? amount) (pos? (double amount))
                                            (<= (double amount) 1000.0)
                                            (finite? aoe-radius)
                                            (<= 0.0 (double aoe-radius) 64.0)
                                            (finite? charge-ticks)
                                            (<= 40.0 (double charge-ticks) 60.0)
                                            (finite? cooldown-multiplier)
                                            (<= 1.0 (double cooldown-multiplier) 1.2)
                                            (world-effects/available?))
                               plan (assoc effect :charge-ticks (long charge-ticks))]
                           {:status (if (and valid?
                                              (world-effects/execute-thunder-clap!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :blood-retrograde
                         (let [{:keys [world-id query-result amount max-charge-ticks
                                       entity-search-radius spray-angles]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id (map? query-result)
                                            (finite? amount) (pos? (double amount))
                                            (<= (double amount) 1000.0)
                                            (finite? max-charge-ticks)
                                            (<= 1.0 (double max-charge-ticks) 64.0)
                                            (finite? entity-search-radius)
                                            (<= 0.0 (double entity-search-radius) 16.0)
                                            (vector? spray-angles)
                                            (<= 1 (count spray-angles) 16)
                                            (every? #(and (finite? %) (<= -180.0 (double %) 180.0))
                                                    spray-angles)
                                            (world-effects/available?))
                               plan (assoc effect :max-charge-ticks (long max-charge-ticks))]
                           {:status (if (and valid?
                                              (world-effects/execute-blood-retrograde!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :electron-missile
                         (let [{:keys [world-id query-result damage seek-range
                                       spawn-interval fire-interval max-balls max-hold-ticks
                                       attack-cp attack-overload]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :damage (double (or damage 0.0))
                                     :seek-range (double (or seek-range 0.0))
                                     :spawn-interval (long (or spawn-interval 10))
                                     :fire-interval (long (or fire-interval 8))
                                     :max-balls (long (or max-balls 5))
                                     :max-hold-ticks (long (or max-hold-ticks 200))
                                     :attack-cp (double (or attack-cp 0.0))
                                     :attack-overload (double (or attack-overload 0.0))}
                               valid? (and world-id (map? query-result)
                                            (finite? damage) (<= 0.0 (:damage plan) 1000.0)
                                            (finite? seek-range) (<= 1.0 (:seek-range plan) 32.0)
                                            (<= 1 (:spawn-interval plan) 40)
                                            (<= 1 (:fire-interval plan) 40)
                                            (<= 1 (:max-balls plan) 5)
                                            (<= 1 (:max-hold-ticks plan) 400)
                                            (finite? attack-cp) (<= 0.0 (:attack-cp plan) 1000.0)
                                            (finite? attack-overload) (<= 0.0 (:attack-overload plan) 1000.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-electron-missile!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :scatter-bomb
                         (let [{:keys [world-id query-result ball-count scatter-range
                                       scatter-angle-degrees auto-aim-radius damage
                                       anti-afk-tick anti-afk-damage]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :ball-count (long (or ball-count 0))
                                     :scatter-range (double (or scatter-range 0.0))
                                     :scatter-angle-degrees (double (or scatter-angle-degrees 0.0))
                                     :auto-aim-radius (double (or auto-aim-radius 0.0))
                                     :damage (double (or damage 0.0))
                                     :anti-afk-tick (long (or anti-afk-tick 200))
                                     :anti-afk-damage (double (or anti-afk-damage 6.0))}
                               valid? (and world-id (map? query-result)
                                            (<= 0 (:ball-count plan) 7)
                                            (finite? scatter-range) (<= 1.0 (:scatter-range plan) 64.0)
                                            (finite? scatter-angle-degrees)
                                            (<= 0.0 (:scatter-angle-degrees plan) 180.0)
                                            (finite? auto-aim-radius) (<= 0.0 (:auto-aim-radius plan) 16.0)
                                            (finite? damage) (<= 0.0 (:damage plan) 1000.0)
                                            (<= 1 (:anti-afk-tick plan) 400)
                                            (finite? anti-afk-damage) (<= 0.0 (:anti-afk-damage plan) 100.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-scatter-bomb!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :plasma-cannon
                         (let [{:keys [world-id query-result charge-ticks
                                       damage explosion-radius]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :charge-ticks (long (or charge-ticks 0))
                                     :damage (double (or damage 0.0))
                                     :explosion-radius (double (or explosion-radius 0.0))}
                               valid? (and world-id (map? query-result)
                                            (<= 1 (:charge-ticks plan) 120)
                                            (finite? damage) (<= 0.0 (:damage plan) 1000.0)
                                            (finite? explosion-radius)
                                            (<= 0.0 (:explosion-radius plan) 32.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-plasma-cannon!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :mine-ray
                         (let [{:keys [world-id scan range break-speed fortune]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:scan scan
                                     :range (double (or range 0.0))
                                     :break-speed (double (or break-speed 0.0))
                                     :fortune (long (or fortune 0))}
                               valid? (and world-id (map? scan)
                                            (finite? range) (<= 1.0 (:range plan) 32.0)
                                            (finite? break-speed) (<= 0.0 (:break-speed plan) 4.0)
                                            (<= 0 (:fortune plan) 3)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-mine-ray!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :meltdowner
                         (let [{:keys [world-id target charge-ticks damage beam-radius
                                       max-distance block-energy reflection]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               reflection (merge {:enabled? true
                                                  :shot-distance 64.0
                                                  :damage-multiplier 1.0}
                                                 (or reflection {}))
                               plan {:target target
                                     :session-id (:session-id effect)
                                     :charge-ticks (long (or charge-ticks 0))
                                     :damage (double (or damage 0.0))
                                     :beam-radius (double (or beam-radius 0.0))
                                     :max-distance (double (or max-distance 0.0))
                                     :block-energy (double (or block-energy 0.0))
                                     :reflection reflection}
                               valid? (and world-id (map? target)
                                            (<= 20 (:charge-ticks plan) 100)
                                            (finite? damage) (<= 0.0 (:damage plan) 1000.0)
                                            (finite? beam-radius) (<= 0.0 (:beam-radius plan) 8.0)
                                            (finite? max-distance) (<= 1.0 (:max-distance plan) 128.0)
                                            (finite? block-energy) (<= 0.0 (:block-energy plan) 1000.0)
                                            (map? reflection)
                                            (boolean (:enabled? reflection))
                                            (finite? (:shot-distance reflection))
                                            (<= 1.0 (double (:shot-distance reflection)) 128.0)
                                            (finite? (:damage-multiplier reflection))
                                            (<= 0.0 (double (:damage-multiplier reflection)) 8.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-meltdowner!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :jet-engine
                         (let [{:keys [world-id query-result charge-ticks target-range
                                       trigger-time-ticks trigger-lifetime-ticks damage]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :charge-ticks (long (or charge-ticks 0))
                                     :target-range (double (or target-range 12.0))
                                     :trigger-time-ticks (long (or trigger-time-ticks 8))
                                     :trigger-lifetime-ticks (long (or trigger-lifetime-ticks 15))
                                     :damage (double (or damage 0.0))}
                               valid? (and world-id (map? query-result)
                                            (<= 0 (:charge-ticks plan) 120)
                                            (finite? target-range) (<= 1.0 (:target-range plan) 32.0)
                                            (<= 1 (:trigger-time-ticks plan) 40)
                                            (<= 1 (:trigger-lifetime-ticks plan) 40)
                                            (finite? damage) (<= 0.0 (:damage plan) 1000.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-jet-engine!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :light-shield
                         (let [{:keys [world-id query-result ticks absorb-damage
                                       touch-damage touch-radius front-cone-degrees
                                       max-active-ticks]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :ticks (long (or ticks 0))
                                     :absorb-damage (double (or absorb-damage 0.0))
                                     :touch-damage (double (or touch-damage 0.0))
                                     :touch-radius (double (or touch-radius 0.0))
                                     :front-cone-degrees (double (or front-cone-degrees 60.0))
                                     :max-active-ticks (long (or max-active-ticks 180))}
                               valid? (and world-id (map? query-result)
                                            (<= 0 (:ticks plan) (:max-active-ticks plan) 180)
                                            (finite? absorb-damage) (<= 0.0 (:absorb-damage plan) 100.0)
                                            (finite? touch-damage) (<= 0.0 (:touch-damage plan) 100.0)
                                            (finite? touch-radius) (<= 0.0 (:touch-radius plan) 8.0)
                                            (finite? front-cone-degrees)
                                            (<= 0.0 (:front-cone-degrees plan) 180.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-light-shield!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :storm-wing
                         (let [{:keys [world-id query-result charge-ticks charge-time
                                       acceleration hover-near-ground-velocity hover-air-velocity
                                       speed-scale speed-threshold]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :charge-ticks (long (or charge-ticks 0))
                                     :charge-time (double (or charge-time 30.0))
                                     :acceleration (double (or acceleration 0.16))
                                     :hover-near-ground-velocity (double (or hover-near-ground-velocity 0.1))
                                     :hover-air-velocity (double (or hover-air-velocity 0.078))
                                     :speed-scale (double (or speed-scale 2.0))
                                     :speed-threshold (double (or speed-threshold 0.45))}
                               valid? (and world-id (map? query-result)
                                            (<= 0 (:charge-ticks plan) 240)
                                            (finite? charge-time) (<= 1.0 (:charge-time plan) 120.0)
                                            (finite? acceleration) (<= 0.0 (:acceleration plan) 1.0)
                                            (finite? hover-near-ground-velocity)
                                            (<= 0.0 (:hover-near-ground-velocity plan) 1.0)
                                            (finite? hover-air-velocity) (<= 0.0 (:hover-air-velocity plan) 1.0)
                                            (finite? speed-scale) (<= 0.0 (:speed-scale plan) 8.0)
                                            (finite? speed-threshold) (<= 0.0 (:speed-threshold plan) 1.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-storm-wing!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :mag-manip
                         (let [{:keys [world-id query-result mode hold-ticks
                                       throw-speed max-hold-distance throw-range
                                       target-policy physics collision-authoritative?]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :mode mode
                                     :hold-ticks (long (or hold-ticks 0))
                                     :throw-speed throw-speed
                                     :max-hold-distance max-hold-distance
                                     :throw-range throw-range
                                     :target-policy target-policy
                                     :physics physics
                                     :collision-authoritative? collision-authoritative?}
                               valid? (and world-id (map? query-result)
                                            (= :throw mode)
                                            (<= 0 (:hold-ticks plan) 200)
                                            (finite? throw-speed)
                                            (<= 0.5 (double throw-speed) 1.0)
                                            (= 5.0 (double max-hold-distance))
                                            (= 20.0 (double throw-range))
                                            (= :metal-block-or-hand target-policy)
                                            (= :tracked-block-body physics)
                                            (= true collision-authoritative?)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-mag-manip!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :mag-movement
                         (let [{:keys [world-id query-result acceleration range
                                       movement-mode target-policy reset-fall-damage?
                                       progression]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :acceleration acceleration
                                     :range range
                                     :movement-mode movement-mode
                                     :target-policy target-policy
                                     :reset-fall-damage? reset-fall-damage?
                                     :progression progression}
                               valid? (and world-id (map? query-result)
                                            (= :target-follow movement-mode)
                                            (= :normal-and-weak-metal target-policy)
                                            (= true reset-fall-damage?)
                                            (= :distance progression)
                                            (finite? acceleration)
                                            (= 0.08 (double acceleration))
                                            (finite? range)
                                            (= 25.0 (double range))
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-mag-movement!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :vec-accel
                         (let [{:keys [world-id query-result charge-ticks max-charge-ticks]} effect
                               valid? (and world-id (map? query-result)
                                            (= 20 (long max-charge-ticks))
                                            (<= 0 (long charge-ticks) 20)
                                            (world-effects/available?))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :charge-ticks (long charge-ticks)
                                     :max-charge-ticks (long max-charge-ticks)}]
                           {:status (if (and valid?
                                              (world-effects/execute-vec-accel!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :vec-deviation
                         (let [{:keys [world-id query-result radius session-id]} effect
                               radius (double (or radius 5.0))
                               valid? (and world-id (map? query-result)
                                            (vector? (:entities query-result))
                                            (<= 0.0 radius 32.0)
                                            (world-effects/available?))
                               plan {:query-result query-result
                                     :session-id session-id
                                     :radius radius}]
                           {:status (if (and valid?
                                              (world-effects/execute-vec-deviation!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :teleport-approved
                         (let [{:keys [target destination radius ability-id]} effect
                               destination (or destination target)
                               location-id (when (map? destination)
                                             (or (:location-id destination)
                                                 (:id destination)
                                                 (:name destination)))
                               radius (double (or radius 5.0))
                               valid? (and (= :location-teleport ability-id)
                                            (string? location-id)
                                            (<= 1 (count location-id) 64)
                                            (<= 0.0 radius 32.0)
                                            (teleportation/available?))
                               applied? (when valid?
                                          (teleportation/teleport-approved-location!
                                           owner ability-id location-id radius))]
                           {:status (if applied? :applied :failed)
                            :effect effect})
                         :teleport-approved-target
                         (let [{:keys [target destination ability-id mode]} effect
                               destination (or destination target)
                               approval-token (when (map? destination)
                                                (or (:approval-token destination)
                                                    (:teleport-token destination)))
                               valid? (and (string? approval-token)
                                           (<= 1 (count approval-token) 128)
                                           (#{:mark-teleport :penetrate-teleport
                                              :shift-teleport :threatening-teleport
                                              :flashing}
                                            ability-id)
                                           (#{:mark :penetrate :shift :threatening :flashing} mode)
                                           (teleportation/available?))
                               applied? (when valid?
                                          (teleportation/teleport-approved-target!
                                           owner ability-id approval-token mode))]
                           {:status (if applied? :applied :failed)
                            :effect effect})
                         {:status :unhandled
                          :reason :missing-world-effect-host-port
                          :effect effect}))))
         @engine*)))))

(defn engine [] (or @engine* (initialize!)))
(defn catalog [] @catalog*)
(defn content-hash [] (:content-hash @catalog*))
(defn register-provider! [provider]
  (registry/register-provider! provider))

(defn- server-session-id []
  (runtime-hooks/player-state-server-session-id))

(defn owner-state
  "Project AC's authoritative player state into Combat Core's neutral view.
   Combat Core never sees the original AC store shape." 
  [owner]
  (let [state (runtime-store/get-player-state (server-session-id) (str owner))
        resource-data (:resource-data state)
        cooldown-data (:cooldown-data state)]
    {:resources {:cp (double (or (:cur-cp resource-data) 0.0))
                 :overload (double (or (:cur-overload resource-data) 0.0))}
     :active-abilities (if-let [engine @engine*]
                         (->> (:sessions (combat/snapshot-owner engine (str owner)))
                              (map :ability-id)
                              set)
                         #{})
     :cooldowns (into {}
                     (map (fn [[[ctrl-id _sub-id] value]]
                            [ctrl-id (long (or (:ticks value) 0))])
                          cooldown-data))
     :ability-data (:ability-data state)
     :preset-data (:preset-data state)}))

(defn resolve-slot
  "Resolve a client slot only against the server-authoritative preset." 
  [owner intent]
  (when-let [state (runtime-store/get-player-state (server-session-id) (str owner))]
    (let [slots (preset-data/get-active-slots (:preset-data state))
          slot (nth slots (long (:slot intent)) nil)]
      (when (and (vector? slot) (= 2 (count slot)))
        (skill-query/get-skill-by-controllable (first slot) (second slot))))))
(defn- commit-state-patch! [owner patches]
  (let [session-id (server-session-id)
        commands (keep (fn [[kind key amount]]
                         (case kind
                           :resource
                           (cond
                             (= key :cp)
                             {:command :consume-resource
                              :cp (- (double amount))}
                             (= key :overload)
                             {:command :consume-resource
                              :overload (- (double amount))}
                             :else nil)
                           :ability-exp
                           {:command :add-skill-exp
                            :skill-id key
                            :amount (double amount)
                            :source :combat-core}
                           :cooldown
                           (let [ticks (max 0 (long (- amount
                                                       (long ((:now-tick (engine)))))))]
                             {:command :set-cooldown
                              :ctrl-id key
                              :sub-id :main
                              :ticks ticks})
                           nil))
                       patches)]
    (when (seq commands)
      (command-runtime/run-commands-in-session! session-id owner commands))))

(defn dispatch-intent! [owner intent]
  (let [result (combat/dispatch-intent! (engine) owner intent)]
    (when (= :accepted (:status result))
      (commit-state-patch! owner (:state-patch result)))
    result))
(defn dispatch-domain-event! [event] (combat/dispatch-domain-event! (engine) event))

(defn process-damage-request!
  "Authoritative damage interception boundary for platform adapters.

   The old mutable damage-handler registry is not consulted. Combat Core
   returns the transformed neutral request; the platform writes only the
   resulting numeric amount back to its event."
  [player-id attacker-id original-damage damage-source]
  (let [request (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :intercepted}
                  :metadata {:damage-source damage-source}})]
    (if (:cancelled? request)
      0.0
      (double (:base request)))))

(defn process-attack-precheck!
  "Route attack prechecks through the authoritative DamageRequest pipeline.

   The removed mutable cancel/precheck registries have no replacement hook;
   combat nodes can cancel through the same deterministic request pipeline."
  [player-id attacker-id original-damage damage-source]
  (let [request (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :attack-precheck}
                  :metadata {:damage-source damage-source}})]
    {:cancelled? (boolean (:cancelled? request))
     :request request}))

(defn install-world-effect-handler!
  "Install AC's ordered WorldEffect interpreter.

   The handler is injected by the platform composition root and receives
   `[owner effect]`. Combat Core never calls it directly; this keeps world
   mutation outside the neutral engine while making effect execution explicit
   and observable." 
  [handler]
  (when-not (ifn? handler)
    (throw (ex-info "world-effect handler must be callable" {:value handler})))
  (reset! world-effect-handler* handler)
  handler)

(defn execute-world-effects!
  "Execute WorldEffects in result order and return EffectResults.

   Missing host wiring is reported as a structured result instead of being
   silently discarded. Resource commits have already happened by this point;
   callers must model compensation explicitly." 
  [owner result]
  (let [handler @world-effect-handler*
        effect-results
        (mapv (fn [effect]
                (if-not handler
                  (contract/effect-result {:status :unhandled
                                           :reason :missing-world-effect-handler
                                           :effect effect})
                  (try
                    (contract/effect-result (handler owner effect))
                    (catch Throwable throwable
                      (contract/effect-result
                       {:status :failed
                        :reason :world-effect-exception
                        :effect effect
                        :message (ex-message throwable)})))))
              (:world-effects result))]
    (assoc result :effect-results effect-results)))
(defn install-result-sink!
  "Install the AC network sink for server-driven session results.

   The sink receives `[owner result]`; Combat Core remains unaware of the
   network transport and only returns neutral result data."
  [sink]
  (when-not (ifn? sink)
    (throw (ex-info "combat result sink must be callable" {:value sink})))
  (reset! result-sink* sink)
  sink)

(defn- publish-result!
  [result]
  (when-let [sink @result-sink*]
    (when-let [owner (:owner result)]
      (sink owner result)))
  result)

(defn tick!
  "Advance sessions, execute their world effects, and publish each result."
  [tick]
  (mapv (fn [result]
          ;; Session pulses produce authoritative resource/cooldown/skill
          ;; patches just like start/release intents.  Commit them before any
          ;; world side effect or client publication so the single AC player
          ;; store remains the source of truth.
          (when (= :accepted (:status result))
            (commit-state-patch! (:owner result) (:state-patch result)))
          (publish-result! (execute-world-effects! (:owner result) result)))
        (combat/tick! (engine) tick)))
(defn abort-owner! [owner] (combat/abort-owner! (engine) owner))
(defn snapshot-owner [owner] (combat/snapshot-owner (engine) owner))

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! world-effect-handler* nil)
  (reset! result-sink* nil)
  nil)
