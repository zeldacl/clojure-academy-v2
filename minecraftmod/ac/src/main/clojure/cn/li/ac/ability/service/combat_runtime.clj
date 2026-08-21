(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.skill-runtime :as combat-skill-runtime]
            [cn.li.combat.reactions :as combat-reactions]
            [cn.li.combat.platform :as combat-platform]
            [cn.li.combat.runtime :as combat]
            [cn.li.combat.targeting :as targeting]
            [cn.li.combat.vm :as combat-vm]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.ac.ability.service.radiation-marks :as radiation-marks]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.combat-sessions :as combat-sessions]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.registry.event :as ability-event]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.util.attack :as attack]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.ac.ability.effects.potion :as potion-effects]
            [cn.li.ac.achievement.dispatcher :as achievement-dispatcher]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.block-manipulation :as block-manipulation]
            [cn.li.mcmod.platform.item :as platform-item]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as position]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]
            [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]
            [cn.li.ac.content.ability.teleporter.location-teleport :as location-teleport]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.block.multiblock-core :as multiblock]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.runtime.combat-contract :as contract]))

(defonce ^:private engine* (atom nil))
(defonce ^:private catalog* (atom nil))
(defonce ^:private world-effect-handler* (atom nil))
(defonce ^:private result-sink* (atom nil))
(defonce ^:private edn-host-capabilities-installed? (atom false))
;; The authoritative source for `:now-tick` when a caller does not supply one.
;; `tick!` below updates this from the real server tick every call; intents
;; dispatched between full tick-loop passes read the last observed value.
(defonce ^:private last-known-tick* (atom 0))
;; Monotonic tiebreaker for activation-seed generation: (owner, ability-id,
;; tick) alone can repeat within a single tick, so a plain hash of those three
;; would make `random/*` behave identically for repeated activations. Mixing
;; in this counter and wall-clock nanos gives every activation its own seed.
(defonce ^:private activation-seed-counter* (atom 0))
(defonce ^:private spawned-entity-ids* (atom {}))
(declare owner-state resolve-slot execute-world-effects! finalize-result! publish-result!)

(defn- generate-activation-seed
  "Produce a fresh per-activation RNG seed. Never deterministic across
  activations for the same owner/ability -- see `dispatch-intent!`'s only
  caller, `execute-combat-intent!`, which threads the result into both the
  program context and the session store so every reader of
  :activation-seed observes the same real value instead of independently
  recomputing a constant fallback."
  [owner ability-id tick]
  (hash [owner ability-id tick
         (swap! activation-seed-counter* unchecked-inc)
         (System/nanoTime)]))

(defn- valid-damage-world-effect?
  [effect]
  (let [request (:request effect)
        base (:base request)]
    (and (= :damage (:type effect))
         (:source request) (:target request)
         (not= (str (:source request)) (str (:target request)))
         (number? base) (Double/isFinite (double base))
         (pos? (double base)) (<= (double base) 10000.0))))

(defn- execute-damage-effects!
  "Execute only validated neutral damage effects emitted by reactions."
  [owner result]
  (let [effects (vec (filter valid-damage-world-effect?
                             (:world-effects result)))]
    (when (= (count effects) (count (:world-effects result)))
      (execute-world-effects! owner (assoc result :world-effects effects)))))

(defn- damage-output?
  [result]
  (and (seq (:world-effects result))
       (some valid-damage-world-effect? (:world-effects result))))

(defn- horizontal-yaw-degrees [x z]
  (- (Math/toDegrees (Math/atan2 (double x) (double z)))))

(defn- attacker-front?
  "Resolve Light Shield's horizontal-yaw cone at the AC boundary.

   Missing entity geometry fails closed. Platform damage sources may provide
   an already validated neutral `:attacker-front?` fact for tests or special
   damage types; ordinary entity damage is resolved through the neutral motion
   and raycast ports here, before Combat Core sees the request."
  [player-id attacker-id damage-source]
  (cond
    (and (map? damage-source) (contains? damage-source :attacker-front?))
    (boolean (:attacker-front? damage-source))

    (nil? attacker-id) true

    (not (and (raycast/available?) (motion-effects/entity-motion-available?)))
    false

    :else
    (try
      (let [position (raycast/player-position (str player-id))
            look (raycast/player-look-vector (str player-id))
            world-id (:world-id position)
            attacker-pos (motion-effects/entity-position world-id (str attacker-id))]
        (boolean
         (when (and (map? position) (map? look) (map? attacker-pos))
           (let [dx (- (double (:x attacker-pos)) (double (:x position)))
                 dz (- (double (:z attacker-pos)) (double (:z position)))
                 player-yaw (horizontal-yaw-degrees (:x look) (:z look))
                 target-yaw (horizontal-yaw-degrees dx dz)
                 diff (mod (Math/abs (double (- target-yaw player-yaw))) 360.0)]
             (< diff (skill-config/tunable-double
                      :light-shield :combat.front-cone-degrees))))))
      (catch Exception _ false))))

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

    :radiation-mark-clear
    (update state :radiation-marks
            (fn [marks]
              (dissoc (or marks {}) (str (:target-id event)))))

    :radiation-marks-clear-all
    (assoc state :radiation-marks {})

    :radiation-replace
    (assoc-in state [:radiation-marks (str (:source-player-id event))]
              (or (:marks event) {}))

    :combat-tick
    (update state :radiation-marks
            #(radiation-marks/tick (or %) (:tick event)))

    :combat-owner-clear
    (update state :radiation-marks
            #(radiation-marks/clear-owner (or %) (:owner event)))

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

(defn- skill-exp-of
  "Mirror combat-core runtime's own private skill-exp lookup (same paths)
   for query implementations that need an ability's exp level directly --
   e.g. to scale a *-dest.clj destination solver's own max-distance."
  [context ability-id]
  (double (or (get-in context [:state :ability-data :skill-exps ability-id])
              (get-in context [:state :skill-exp ability-id])
              0.0)))

(defn- resolve-scale
  "Resolve a raw {:op :scale :min :max} node field against exp. Only
   :distance/:range/:aoe-radius node keys are auto-resolved by combat-core's
   own :query op before a query-port fn ever sees `node` -- other keys like
   :max-range arrive as this unresolved expression."
  [expr exp]
  (if (and (map? expr) (= :scale (:op expr)))
    (let [lo (double (:min expr)) hi (double (:max expr))]
      (+ lo (* (- hi lo) (max 0.0 (min 1.0 (double exp))))))
    (double (or expr 0.0))))

(defn- nearest-entity-in-range
  [world-id origin radius excluded]
  (when (world-effects/available?)
    (let [candidates (->> (world-effects/find-entities-in-radius
                            world-id (double (:x origin)) (double (:y origin)) (double (:z origin))
                            (double radius))
                           (filter map?)
                           (remove #(contains? excluded (str (or (:uuid %) (:entity-id %))))))]
      (when (seq candidates)
        (apply min-key
               (fn [{:keys [x y z]}]
                 (let [dx (- (double (or x 0.0)) (double (:x origin)))
                       dy (- (double (or y 0.0)) (double (:y origin)))
                       dz (- (double (or z 0.0)) (double (:z origin)))]
                   (+ (* dx dx) (* dy dy) (* dz dz))))
               candidates)))))

(defn- horizontal-look
  "Flatten owner's look vector to the horizontal plane and normalize; nil
   when looking straight up/down (degenerate horizontal component). The
   deleted pre-combat-core groundshock defskill's skill_config/vecmanip.clj
   entry for targeting.horizontal-look-fallback defaults to false -- i.e.
   the stock behavior is no fallback, the ability simply doesn't fire when
   aimed straight up or down. That config path has no combat-core
   equivalent, so this always uses the (more common) false default rather
   than guessing which players would have flipped it on."
  [owner]
  (when-let [look (when (raycast/available?) (raycast/player-look-vector owner))]
    (let [x (double (or (:x look) 0.0))
          z (double (or (:z look) 0.0))
          len (Math/sqrt (+ (* x x) (* z z)))]
      (when (> len 1.0e-6)
        {:x (/ x len) :y 0.0 :z (/ z len)}))))

;; --- shift-teleport helpers ------------------------------------------------
;; Ported from the pre-combat-core shift-teleport defskill (deleted in
;; a8c000766, recovered from git history). The skill isn't actually a player
;; teleport -- it raycasts a placement point, places/drops the held item
;; there, and damages whatever intersects the line from the caster to that
;; point. Pure geometry only; nothing here touches a live Player object, so
;; it can run in the query (AC layer). The hand-item mutation (place/drop/
;; consume) needs a resolved Player object that only platform-src has
;; (query-core/get-player-by-uuid) -- see execute-shift-teleport!.

(def ^:private shift-teleport-face-offsets
  {:up [0 1 0] :down [0 -1 0]
   :north [0 0 -1] :south [0 0 1]
   :west [-1 0 0] :east [1 0 0]})

(defn- shift-teleport-segment-intersects-aabb?
  "True when segment p0->p1 intersects axis-aligned box {min-x..max-z}."
  [{:keys [x y z]} p1 {:keys [min-x min-y min-z max-x max-y max-z]}]
  (let [dx (- (double (:x p1)) (double x))
        dy (- (double (:y p1)) (double y))
        dz (- (double (:z p1)) (double z))
        axis-step (fn [p d mn mx tmin tmax]
                    (if (< (Math/abs (double d)) 1.0e-9)
                      (if (or (< p mn) (> p mx)) nil [tmin tmax])
                      (let [inv (/ 1.0 d)
                            t1 (* (- mn p) inv)
                            t2 (* (- mx p) inv)
                            lo (min t1 t2)
                            hi (max t1 t2)
                            ntmin (max tmin lo)
                            ntmax (min tmax hi)]
                        (when (<= ntmin ntmax) [ntmin ntmax]))))]
    (when-let [[tmin tmax] (axis-step (double x) dx (double min-x) (double max-x) 0.0 1.0)]
      (when-let [[tmin tmax] (axis-step (double y) dy (double min-y) (double max-y) tmin tmax)]
        (when-let [[_ _] (axis-step (double z) dz (double min-z) (double max-z) tmin tmax)]
          true)))))

(defn- shift-teleport-entity-aabb
  [entity]
  (let [x (double (:x entity)) y (double (:y entity)) z (double (:z entity))
        half-w (/ (double (or (:width entity) 0.6)) 2.0)
        h (double (or (:height entity) 1.8))]
    {:min-x (- x half-w) :max-x (+ x half-w)
     :min-y y :max-y (+ y h)
     :min-z (- z half-w) :max-z (+ z half-w)}))

(defn- shift-teleport-point-line-distance-sq
  [{sx :x sy :y sz :z} {ex :x ey :y ez :z} {px :x py :y pz :z}]
  (let [vx (- (double ex) (double sx)) vy (- (double ey) (double sy)) vz (- (double ez) (double sz))
        wx (- (double px) (double sx)) wy (- (double py) (double sy)) wz (- (double pz) (double sz))
        len-sq (+ (* vx vx) (* vy vy) (* vz vz))
        t (if (pos? len-sq)
            (max 0.0 (min 1.0 (/ (+ (* wx vx) (* wy vy) (* wz vz)) len-sq)))
            0.0)
        qx (+ (double sx) (* vx t)) qy (+ (double sy) (* vy t)) qz (+ (double sz) (* vz t))
        dx (- (double px) qx) dy (- (double py) qy) dz (- (double pz) qz)]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn- shift-teleport-line-targets
  "Entities intersecting segment line-from->line-to, nearest-first, self
   excluded, deduped by uuid."
  [owner world-id line-from line-to]
  (if-not (and (world-effects/available?) line-from line-to)
    []
    (let [min-x (min (double (:x line-from)) (double (:x line-to)))
          min-y (min (double (:y line-from)) (double (:y line-to)))
          min-z (min (double (:z line-from)) (double (:z line-to)))
          max-x (max (double (:x line-from)) (double (:x line-to)))
          max-y (max (double (:y line-from)) (double (:y line-to)))
          max-z (max (double (:z line-from)) (double (:z line-to)))
          candidates (world-effects/find-entities-in-aabb
                      world-id min-x min-y min-z max-x max-y max-z)]
      (->> candidates
           (filter (fn [entity]
                     (let [uuid (str (:uuid entity))]
                       (and (seq uuid) (not= uuid (str owner))
                            (shift-teleport-segment-intersects-aabb?
                             line-from line-to (shift-teleport-entity-aabb entity))))))
           (sort-by (partial shift-teleport-point-line-distance-sq line-from line-to))
           (reduce (fn [acc entity]
                     (let [uuid (str (:uuid entity))]
                       (if (contains? (:seen acc) uuid)
                         acc
                         {:seen (conj (:seen acc) uuid) :entities (conj (:entities acc) entity)})))
                   {:seen #{} :entities []})
           :entities))))

;; `:runtime-interop :get-block-entity-at` is a neutral AC host adapter used
;; by the generic energy query/action ports below. It returns an opaque tile
;; only inside this composition root; no tile or Minecraft object crosses the
;; Combat Core contract.
(defn- block-entity-at
  [world-id x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-block-entity-at world-id x y z)))

(defn- held-item-at
  [owner]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :get-player-main-hand-item (str owner))))

(defn- resolve-energy-tile
  "Resolve a neutral block position to the controller tile when the platform
  exposes a multiblock controller.  The query/action boundary never returns
  this tile object; it is used only inside the AC host function."
  [world-id block-pos]
  (when (and world-id (vector? block-pos) (= 3 (count block-pos)))
    (let [[x y z] (map long block-pos)
          tile (block-entity-at world-id x y z)]
      (or
       (try
         (when-let [level (platform-be/be-get-world-safe tile)]
           (let [block-id (platform-be/get-block-id tile)
                 controller (when (and block-id)
                              (multiblock/resolve-controller-pos
                               {:world level :pos (position/create-block-pos x y z)
                                :block-id block-id}))]
             (when controller
               (world/get-tile-entity level controller))))
         (catch Throwable _ nil))
       tile))))

(defn- energy-target-result
  [world-id hit]
  (let [block-pos (or (when (map? (:block-position hit))
                        (let [{:keys [x y z]} (:block-position hit)]
                          (when (every? number? [x y z])
                            [(long (Math/floor (double x)))
                             (long (Math/floor (double y)))
                             (long (Math/floor (double z)))])))
                      (:block-position hit)
                      (when (and (= :block (:hit-type hit))
                                 (every? number? [(:x hit) (:y hit) (:z hit)]))
                        [(long (Math/floor (double (:x hit))))
                         (long (Math/floor (double (:y hit))))
                         (long (Math/floor (double (:z hit))))]))
        tile (resolve-energy-tile world-id block-pos)
        supported? (boolean (and tile
                                 (or (energy/is-node-supported? tile)
                                     (energy/is-receiver-supported? tile))))]
    {:chargeable? supported?
     :block-pos block-pos
     :block-bounds (when (and tile block-pos)
                     (try
                       (let [level (platform-be/be-get-world-safe tile)
                             block-id (platform-be/get-block-id tile)]
                         (when (and level block-id)
                           (multiblock/structure-bounds
                            {:world level
                             :pos (apply position/create-block-pos block-pos)
                             :block-id block-id})))
                       (catch Throwable _ nil)))}))

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
           (let [target (str (:target request))
                 mark (get-in context [:domain-state :radiation-marks target])
                 ticks-left (long (or (:ticks-left mark) 0))
                 rate (double (or (:rate mark) 1.0))
                 base (double (:base request))]
             (if (and mark (pos? ticks-left)
                      (Double/isFinite base)
                      (Double/isFinite rate)
                      (<= 0.0 rate 8.0))
               (-> request
                   (assoc :base (* base rate))
                   (assoc-in [:metadata :radiation-mark]
                             {:source-player-id (:source-player-id mark)
                              :target-id target
                              :rate rate
                              :ticks-left ticks-left}))
               request)))}
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
                 cp-limit (max 0.0 (+ 15.0 (* -3.0 exp)))
                 cp-available (max 0.0
                                   (double
                                    (or (get-in (or (:target-state context)
                                                    (:state context) {})
                                                [:resources :cp])
                                        0.0)))
                 cp-cost (min cp-available cp-limit)]
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
                                 (assoc :world-id (:world-id position))
                                 ;; Caster origin (eye position), so a :vfx step
                                 ;; can draw a beam from :eye-x/:eye-y/:eye-z to
                                 ;; the existing hit-x/hit-y/hit-z without a
                                 ;; second query — player-position already
                                 ;; fetches this for :world-id above, it was
                                 ;; just discarded.
                                 (map? position)
                                 (assoc :eye-x (:x position)
                                        :eye-y (:eye-y position)
                                        :eye-z (:z position)))))))
              :entities (fn [context node]
                          (when-let [host-query (contract/host-port :query)]
                            (host-query :entities context node)))
               :block-scan (fn [context node]
                            (if-let [host-query (contract/host-port :query)]
                              (host-query :block-scan context node)
                              (let [owner (:owner context)
                                    world-id (geom/world-id-of owner)
                                    eye (geom/eye-pos owner)
                                    look (when (raycast/available?)
                                           (raycast/player-look-vector owner))
                                    distance (double (or (:distance node) 10.0))]
                                (when (and look (block-manipulation/available?))
                                  (when-let [hit (first (block-manipulation/find-blocks-in-line
                                                         world-id (:x eye) (:y eye) (:z eye)
                                                         (double (or (:x look) 0.0))
                                                         (double (or (:y look) 0.0))
                                                         (double (or (:z look) 1.0))
                                                         distance))]
                                    (assoc hit :world-id world-id))))))
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
              ;; Ground-level start point + flattened look direction only --
              ;; the actual DDA propagation walk, block breaking and entity
              ;; sweep all happen in execute-groundshock! (platform-src),
              ;; matching this file's convention that :world-effect always
              ;; delegates the real work. Previously delegated to :block-scan
              ;; (a single raycast hit), which is a different shape entirely
              ;; and was never actually consumed by an installed executor.
              :groundshock (fn [context node]
                             (if-let [host-query (contract/host-port :query)]
                               (host-query :groundshock context node)
                               (let [owner (:owner context)
                                     world-id (geom/world-id-of owner)
                                     body (geom/body-pos owner)
                                     look (horizontal-look owner)]
                                 (when (and world-id body look)
                                   {:world-id world-id
                                    :x (double (:x body))
                                    :y (dec (double (:y body)))
                                    :z (double (:z body))
                                    :look-x (:x look)
                                    :look-y (:y look)
                                    :look-z (:z look)}))))
              :blood-retrograde (fn [context node]
                                  (if-let [host-query (contract/host-port :query)]
                                    (host-query :blood-retrograde context node)
                                    ((get-in context [:queries :raycast])
                                     context (assoc node :query-type :raycast))))
              :electron-missile (fn [context node]
                                  (if-let [host-query (contract/host-port :query)]
                                    (host-query :electron-missile context node)
                                    ;; execute-electron-missile! does its own
                                    ;; fresh nearest-target search every fire;
                                    ;; :require never gates on this step's
                                    ;; result (see combat_content.clj), so a
                                    ;; non-nil map is all :world-effect needs.
                                    {}))
              :saved-location (fn [context node]
                                (if-let [host-query (contract/host-port :query)]
                                  (host-query :saved-location context node)
                                  ;; No "home"/primary-location convention
                                  ;; exists anywhere in this codebase (the
                                  ;; RPC-driven UI in location_teleport.clj
                                  ;; always teleports by an explicit name the
                                  ;; player picked). For a hotbar-slot
                                  ;; activation with no name input, fall back
                                  ;; to the alphabetically-first saved name --
                                  ;; deterministic and simple, but an inferred
                                  ;; UX choice, not a confirmed design. Revisit
                                  ;; if it turns out players expect something
                                  ;; else (most-recently-saved, a reserved
                                  ;; "home" name, ...).
                                  (let [owner (:owner context)
                                        locations (:locations
                                                   (location-teleport/query-location-teleport (str owner)))
                                        location-name (->> locations (map :name) sort first)]
                                    (when location-name {:location-id location-name}))))
              :teleport-target
              (fn [context node]
                (if-let [host-query (contract/host-port :query)]
                  (host-query :teleport-target context node)
                  (let [owner (:owner context)
                        world-id (geom/world-id-of owner)
                        exp (skill-exp-of context (:ability-id context))
                        cp (double (or (get-in context [:state :resources :cp]) 0.0))
                        hold-ticks (double (or (get-in context [:session-state :hold-ticks]) 0.0))
                        head-blocked? (fn [x y z]
                                        (block-manipulation/block-collidable?
                                         world-id x (inc (long y)) z))]
                    (case (:mode node)
                      :threatening
                      (let [range (resolve-scale (:max-range node) exp)
                            eye (geom/eye-pos owner)
                            target (nearest-entity-in-range world-id eye range #{(str owner)})]
                        (when target
                          {:entity-uuid (:uuid target)}))

                      nil))))
              ;; shift-teleport isn't a player teleport at all -- it's "place
              ;; or drop the held item at a raycasted point, then damage
              ;; whatever intersects the line from caster to that point".
              ;; Gets its own query-type/effect-type rather than piggybacking
              ;; on :teleport-target/:teleport-approved-target, matching this
              ;; file's convention for skills with a genuinely distinct
              ;; mechanic (mag-manip, groundshock, knockback).
              :shift-teleport
              (fn [context node]
                (if-let [host-query (contract/host-port :query)]
                  (host-query :shift-teleport context node)
                  (let [owner (:owner context)
                        world-id (geom/world-id-of owner)
                        eye (geom/eye-pos owner)
                        body (geom/body-pos owner)
                        look (when (raycast/available?) (raycast/player-look-vector owner))
                        exp (skill-exp-of context (:ability-id context))
                        max-range (resolve-scale (or (:max-range node) 25.0) exp)]
                    (when (and world-id eye body look)
                      (let [lx (double (or (:x look) 0.0))
                            ly (double (or (:y look) 0.0))
                            lz (double (or (:z look) 1.0))
                            hit (when (raycast/available?)
                                  (raycast/raycast-blocks
                                   world-id (:x eye) (:y eye) (:z eye) lx ly lz max-range))
                            face (or (:face hit) :down)
                            [ox oy oz] (get shift-teleport-face-offsets face [0 -1 0])
                            endpoint-x (+ (:x eye) (* lx max-range))
                            endpoint-y (+ (:y eye) (* ly max-range))
                            endpoint-z (+ (:z eye) (* lz max-range))
                            hit-block-x (if hit (long (:x hit)) (long (Math/floor endpoint-x)))
                            hit-block-y (if hit (long (:y hit)) (long (Math/floor endpoint-y)))
                            hit-block-z (if hit (long (:z hit)) (long (Math/floor endpoint-z)))
                            place-x (+ hit-block-x ox)
                            place-y (+ hit-block-y oy)
                            place-z (+ hit-block-z oz)
                            dest-block-x (if hit place-x (long endpoint-x))
                            dest-block-y (if hit place-y (long endpoint-y))
                            dest-block-z (if hit place-z (long endpoint-z))
                            drop-x (if hit (+ (double (:hit-x hit)) ox) endpoint-x)
                            drop-y (if hit (+ (double (:hit-y hit)) oy) endpoint-y)
                            drop-z (if hit (+ (double (:hit-z hit)) oz) endpoint-z)
                            dest-x (+ (double dest-block-x) 0.5)
                            dest-y (double dest-block-y)
                            dest-z (+ (double dest-block-z) 0.5)
                            line-end {:x dest-x :y (+ dest-y 0.5) :z dest-z}
                            entities (shift-teleport-line-targets owner world-id body line-end)]
                        {:world-id world-id
                         :hit-block-x hit-block-x :hit-block-y hit-block-y :hit-block-z hit-block-z
                         :place-x place-x :place-y place-y :place-z place-z
                         :face face
                         :drop-x drop-x :drop-y drop-y :drop-z drop-z
                         :target-entities (mapv #(select-keys % [:uuid]) entities)})))))
              :vec-accel (fn [context node]
                           (if-let [host-query (contract/host-port :query)]
                             (host-query :vec-accel context node)
                             (vec-accel-query context node)))
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
                            ;; Explicit `(or now-tick ...)`: create-engine's :or
                            ;; default only fires when the key is absent, and
                            ;; this map always includes :now-tick (possibly
                            ;; nil when initialize! is called with {}), so an
                            ;; unguarded pass-through here silently binds the
                            ;; engine's now-tick to nil.
                            :now-tick (or now-tick (fn [] @last-known-tick*))
                            :ability-resolver (or ability-resolver resolve-slot)
                            :domain-event-handler (or domain-event-handler
                                                       apply-combat-domain-event)
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
                         ;; valid?'s drop-rate check was dead code before this
                         ;; session: it expected a 2-element vector, but
                         ;; combat-core's :world-effect op runs resolve-data
                         ;; recursively over the whole node (including nested
                         ;; map values like :breaking), so content's
                         ;; :drop-rate (scale 0.3 1.0) always arrives here
                         ;; already resolved to a plain double -- the vector
                         ;; shape this checked for can never occur. Since
                         ;; execute-groundshock! was never installed, this
                         ;; branch's valid? was never exercised until now.
                         :groundshock
                         (let [{:keys [world-id query-result amount max-iterations
                                       init-energy entity-search-radius launch-scale
                                       launch-random-base launch-random-span breaking
                                       energy-cost]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               {:keys [drop-rate ground-break-probability]} breaking
                               {:keys [stone grass-block farmland default-block]} energy-cost
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
                                            (finite? drop-rate)
                                            (<= 0.0 (double drop-rate) 1.0)
                                            (finite? ground-break-probability)
                                            (<= 0.0 (double ground-break-probability) 1.0)
                                            (every? finite? [stone grass-block farmland default-block])
                                            (every? #(<= 0.0 (double %) 10.0)
                                                    [stone grass-block farmland default-block])
                                            (world-effects/available?))
                               plan (assoc effect :max-iterations (long max-iterations))]
                           {:status (if (and valid?
                                              (world-effects/execute-groundshock!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         :shift-teleport
                         (let [{:keys [world-id query-result damage]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id (map? query-result)
                                            (every? #(number? (get query-result %))
                                                    [:hit-block-x :hit-block-y :hit-block-z
                                                     :place-x :place-y :place-z
                                                     :drop-x :drop-y :drop-z])
                                            (finite? damage) (pos? (double damage))
                                            (<= (double damage) 1000.0)
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/execute-shift-teleport!
                                               world-id owner effect))
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
                         (let [{:keys [world-id target destination ability-id mode damage]} effect
                               destination (or destination target)]
                           (case mode
                             ;; threatening-teleport is not a player teleport
                             ;; ("teleport a small fragment into the target" --
                             ;; see docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
                             ;; A section): it moves a target entity's fate,
                             ;; not the caster's position, so it applies
                             ;; damage directly instead of minting a token and
                             ;; calling teleport-approved-target!.
                             :threatening
                             (let [target-uuid (when (map? destination)
                                                  (or (:uuid destination)
                                                      (:entity-uuid destination)
                                                      (:target-uuid destination)))
                                   finite? #(and (number? %) (Double/isFinite (double %)))
                                   valid? (and (= :threatening-teleport ability-id)
                                                world-id target-uuid
                                                (finite? damage) (pos? (double damage))
                                                (<= (double damage) 1000.0)
                                                (entity-damage/available?))
                                   applied? (when valid?
                                              (entity-damage/apply-direct-damage!
                                               world-id target-uuid (double damage) :vector
                                               {:attacker-uuid owner}))]
                               {:status (if applied? :applied :failed)
                                :effect effect})
                             {:status :unhandled
                              :reason :unsupported-teleport-mode
                              :effect effect}))
                         :knockback
                         (let [{:keys [world-id target movement]} effect
                               {:keys [impulse knockback-y-adjust knockback-scale]} movement
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id target
                                            (finite? impulse) (<= 0.0 (double impulse) 4.0)
                                            (finite? knockback-y-adjust) (<= -2.0 (double knockback-y-adjust) 2.0)
                                            (finite? knockback-scale) (<= -2.0 (double knockback-scale) 2.0)
                                            (world-effects/available?))
                               plan {:target target
                                     :impulse (double (or impulse 0.0))
                                     :knockback-y-adjust (double (or knockback-y-adjust 0.0))
                                     :knockback-scale (double (or knockback-scale 1.0))}]
                           {:status (if (and valid?
                                              (world-effects/execute-knockback!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         {:status :unhandled
                          :reason :missing-world-effect-host-port
                          :effect effect}))))
         @engine*)))))

(defn engine [] (or @engine* (initialize!)))
(defn catalog [] @catalog*)
(defn content-hash [] (:content-hash @catalog*))
(defn domain-state [] (combat/domain-state (engine)))
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
     :active-abilities (if-let [session (combat-sessions/session (str owner))]
                         #{(:ability-id session)}
                         #{})
     ;; {ability-id {sub-id ticks}} -- keyed by BOTH ctrl-id and sub-id, unlike
     ;; the flattened {ctrl-id ticks} this used to project, which silently
     ;; collapsed an ability with more than one named cooldown onto a single
     ;; value. Combat Core's cooldown gate (skill_runtime/dispatch!) reads
     ;; this shape directly.
     :cooldowns (reduce (fn [acc [[ctrl-id sub-id] value]]
                          (assoc-in acc [ctrl-id sub-id]
                                    (long (or (:ticks value) 0))))
                        {} cooldown-data)
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
                           (let [ticks (max 0 (long (- amount @last-known-tick*)))]
                             {:command :set-cooldown
                              :ctrl-id key
                              :sub-id :main
                              :ticks ticks})
                           nil))
                       patches)]
    (when (seq commands)
      (command-runtime/run-commands-in-session! session-id owner commands))))

(defn- edn-owner-patch-commands
  "Translate the neutral owner-patch contract into AC reducer commands.

  Core never knows AC's player-state layout; this adapter is the only place
  where neutral paths become persistent state transitions.

  Every entry in every owner-patch action is translated independently: an
  action carrying both a cp entry and an overload entry must commit both,
  not just the first one a scan happens to hit."
  [patch-actions]
  (mapcat (fn [{:keys [entries]}]
            (keep (fn [{:keys [path mode value]}]
                    (let [amount (when (number? value) (double value))]
                      (cond
                        (and (= mode :increment)
                             (= path [:resources :cp])
                             (some? amount))
                        {:command :consume-resource :cp (- amount)}
                        (and (= mode :increment)
                             (= path [:resources :overload])
                             (some? amount))
                        {:command :consume-resource :overload (- amount)}
                        (and (= mode :increment)
                             (= 3 (count path))
                             (= [:ability-data :skill-exps] (subvec (vec path) 0 2))
                             (keyword? (nth path 2))
                             (some? amount))
                        {:command :add-skill-exp
                         :skill-id (nth path 2)
                         :amount amount
                         :source :combat-core}
                        (and (= mode :assign)
                             (= 3 (count path))
                             (= [:cooldown-data] (subvec (vec path) 0 1))
                             (keyword? (nth path 1))
                             (keyword? (nth path 2))
                             (some? amount))
                        {:command :set-cooldown
                         :ctrl-id (nth path 1)
                         :sub-id (nth path 2)
                         :ticks (max 0 (long amount))}
                        :else nil)))
                  entries))
          (filter #(= :owner-patch (:type %)) patch-actions)))

(defn- commit-edn-owner-patches!
  [owner actions]
  (let [patch-actions (vec (filter #(= :owner-patch (:type %)) actions))
        commands (vec (edn-owner-patch-commands patch-actions))]
    (when (seq commands)
      (let [result (command-runtime/run-commands-in-session!
                    (server-session-id) owner commands)]
        [{:status (if (:success? result) :committed :failed)
          :capability :owner-patch
          :command-count (count commands)}]))))

(defn- edn-ability-id [owner intent]
  (or (:ability-id intent)
      (:ability intent)
      (some-> (resolve-slot owner intent) :id)))


(defn- activation-context
  [owner ability-id intent seed]
  (let [state (runtime-store/get-player-state (server-session-id) (str owner))
        resource-data (:resource-data state)
        position (when (raycast/available?)
                   (raycast/player-position (str owner)))
        eye (geom/eye-pos (str owner))
        look (when (raycast/available?)
               (raycast/player-look-vector (str owner)))]
    (merge {:owner owner
            :ability-id ability-id
            :world-id (or (:world-id position) (geom/world-id-of (str owner)))
            :eye-pos eye
            :look look
            :activation-seed (long seed)
            :skill-exp (double (or (get-in state [:ability-data :skill-exps ability-id])
                                   0.0))
            ;; Generic progression metadata exposed through the caster
            ;; facade.  Mine Detect uses it to select its configurable
            ;; presentation tier; it is not a skill-specific runtime hook.
            :ability-level (long (or (get-in state [:ability-data :level]) 0))
            :resources {:cp (double (or (:cur-cp resource-data) 0.0))
                        :overload (double (or (:cur-overload resource-data) 0.0))}
            :creative? (boolean (:creative? intent))}
           (:context intent))))

(defn- caster-facade
  "Schema v2 design C: the neutral capability table an EDN ability reads via
  `{:from :caster/...}` instead of `{:ref [:context ...]}` reaching straight
  into AC's own context shape. This is the ONLY place that shape is allowed
  to leak into a form combat-core sees -- change AC's context layout and
  only this function needs updating, never any EDN.

  Deliberately incomplete: `:caster/hand-item` and `:toggle/enabled?` are
  not wired yet (the former needs a new cross-platform held-item port, the
  latter needs the same session-presence check the damage-reaction path
  already computes at combat_runtime.clj ~2394). Referencing either from
  EDN fails closed (`combat-core/vm.clj`'s :from resolution throws) rather
  than silently resolving to nil; both get added in Phase 5 alongside the
  abilities that actually need them."
  [owner context]
  (let [look (or (:look context) {:x 0.0 :y 0.0 :z 1.0})
        lx (double (or (:x look) 0.0))
        ly (double (or (:y look) 0.0))
        lz (double (or (:z look) 1.0))
        length (max 1.0e-9 (Math/sqrt (+ (* lx lx) (* ly ly) (* lz lz))))
        forward {:x (/ lx length) :y (/ ly length) :z (/ lz length)}
        left-length (max 1.0e-9 (Math/sqrt (+ (* lz lz) (* lx lx))))]
    {:caster/eye (:eye-pos context)
     :caster/body (geom/body-pos (str owner))
     :caster/eye-y (double (or (:y (:eye-pos context)) 0.0))
     :caster/aim look
     :caster/id owner
     :world/id (:world-id context)
     :caster/creative? (boolean (:creative? context))
     ;; Configured target registries are snapshotted at activation and exposed
     ;; as neutral lists.  EDN never reaches back into AC config paths.
     :targeting/normal-metal-blocks (ability-config/get-normal-metal-blocks)
     :targeting/weak-metal-blocks (ability-config/get-weak-metal-blocks)
     :targeting/metal-entities (ability-config/get-metal-entities)
     :movement/forward forward
     :movement/back {:x (- (:x forward)) :y (- (:y forward)) :z (- (:z forward))}
     :movement/left {:x (/ lz left-length) :y 0.0 :z (- (/ lx left-length))}
     :movement/right {:x (- (/ lz left-length)) :y 0.0 :z (/ lx left-length)}
     :charge/ticks (long (or (:hold-ticks context) 0))
   ;; Raw (pre-curve) mastery and RNG seed: legitimate exceptions to design
   ;; B/E folding skill-exp/seed away. Some content hands both to an AC-side
   ;; domain-event handler that isn't itself an EDN node (arc-gen's ignite/
   ;; fishing resolution) -- that handler needs the same inputs the VM's own
   ;; :expr evaluator would have used, just not through a lerp/random/* node.
   :progression/mastery (double (or (:skill-exp context) 0.0))
     :progression/level (long (or (:ability-level context) 0))
     :rng/seed (long (or (:activation-seed context) 0))}))

(defn- lerp [lo hi t]
  (let [bounded (max 0.0 (min 1.0 (double t)))]
    (+ (double lo) (* (- (double hi) (double lo)) bounded))))


(defn- raycast-point
  [hit key fallback]
  (double (or (get hit key) fallback 0.0)))

(defn- penetration-destination
  "Resolve the generic wall-through march against AC's neutral block port.

   The component and marcher carry no skill id or config lookup.  This host
   adapter only translates the platform collision predicate into the generic
   targeting function and adds the requested visual anchor offset."
  [owner {:keys [origin direction distance policy world-id]}]
  (let [world-id (or (when (and world-id (not= "unknown" (str world-id)))
                      (str world-id))
                    (geom/world-id-of (str owner)))
        result (targeting/march-through-collision
                origin direction distance
                (:scan-step policy)
                (:clearance-steps policy)
                (fn [x y z]
                  (or (nil? world-id)
                      (not (block-manipulation/available?))
                      (block-manipulation/block-collidable?
                       world-id (int x) (int y) (int z)))))]
    (when result
      (let [position (:position result)
            offset (double (or (:marker-offset-y policy) 0.0))]
        (assoc result
               :marker-position (update position :y + offset)
               :hit? false)))))

(defn- directional-destination
  "Adapt the neutral directional landing query to AC raycast/block ports."
  [owner {:keys [origin look eye-y direction distance policy world-id]}]
  (let [owner (str owner)
        world-id (or (when (and world-id (not= "unknown" (str world-id)))
                       (str world-id))
                     (geom/world-id-of owner))
        raycast-fn (fn [sx sy sz dx dy dz max-distance]
                     (when (and world-id (raycast/available?))
                       (raycast/raycast-combined-excluding
                        world-id sx sy sz dx dy dz max-distance owner)))
        head-blocked? (fn [x y z]
                        (and world-id
                             (block-manipulation/available?)
                             (block-manipulation/block-collidable?
                              world-id (int (Math/floor (double x)))
                              (int (Math/floor (+ (double y) 1.0)))
                              (int (Math/floor (double z))))))]
    (targeting/directional-destination
     {:origin origin :look look :eye-y eye-y :direction direction
      :distance distance :policy policy :raycast raycast-fn
      :head-blocked? head-blocked?})))

(defn- resolve-raycast-destination
  "Resolve a neutral raycast hit into a safe landing point.

   This is deliberately a generic host operation: EDN supplies the offsets
   and minimum-distance policy, while AC supplies only the platform reads
   needed to test the block above a side-face landing spot.  No ability id or
   skill-specific branch belongs here."
  [owner {:keys [hit origin distance policy world-id]}]
  (let [hit (when (map? hit) hit)
        origin (or origin {})
        [ox oy oz] (if (vector? (:vec3 origin))
                     (:vec3 origin)
                     [(double (or (:x origin) 0.0))
                      (double (or (:y origin) 0.0))
                      (double (or (:z origin) 0.0))])
        distance (double (or distance 0.0))
        look (or (:direction policy) {})
        [dx dy dz] (if (vector? (:vec3 look))
                     (:vec3 look)
                     [(double (or (:x look) 0.0))
                      (double (or (:y look) 0.0))
                      (double (or (:z look) 0.0))])
        entity-eye-height (double (or (:eye-height hit)
                                      (:entity-eye-height policy)
                                      1.6))
        block-y (raycast-point hit :y 0.0)
        hx (raycast-point hit :hit-x (:x hit))
        hy (raycast-point hit :hit-y (:y hit))
        hz (raycast-point hit :hit-z (:z hit))
        kind (:hit-type hit)
        face (:face hit)
        head-blocked?
        (fn [x y z]
          (let [world-id (or world-id (geom/world-id-of (str owner)))]
            (and world-id
                 (block-manipulation/block-collidable?
                  world-id (int (Math/floor (double x)))
                  (int (Math/floor (+ (double y) 1.0)))
                  (int (Math/floor (double z)))))))
        destination
        (if (= :entity kind)
          {:x (raycast-point hit :x hx)
           :y (+ (raycast-point hit :y hy) entity-eye-height)
           :z (raycast-point hit :z hz)}
          (case face
            :down  {:x hx :y (- hy 1.0) :z hz}
            :up    {:x hx :y (+ hy 1.8) :z hz}
            :north {:x hx :y (+ block-y 1.7) :z (- hz 0.6)}
            :south {:x hx :y (+ block-y 1.7) :z (+ hz 0.6)}
            :west  {:x (- hx 0.6) :y (+ block-y 1.7) :z hz}
            :east  {:x (+ hx 0.6) :y (+ block-y 1.7) :z hz}
            {:x hx :y hy :z hz}))
        destination
        (if (and (#{:north :south :west :east} face)
                 (:head-clearance? policy)
                 (head-blocked? (:x destination) (:y destination) (:z destination)))
          (update destination :y - 1.25)
          destination)
        miss? (or (nil? hit) (= :miss kind))
        destination (if miss?
                      {:x (+ ox (* dx distance))
                       :y (+ oy (* dy distance))
                       :z (+ oz (* dz distance))}
                      destination)
        ddx (- (double (:x destination)) ox)
        ddy (- (double (:y destination)) oy)
        ddz (- (double (:z destination)) oz)
        resolved-distance (Math/sqrt (+ (* ddx ddx) (* ddy ddy) (* ddz ddz)))
        minimum-distance (double (or (:minimum-distance policy) 0.0))]
    {:position destination
     :distance resolved-distance
     :hit? (not miss?)
     :valid? (>= resolved-distance minimum-distance)}))

(defn- neutral-point
  [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (mapv double (:vec3 value))
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else nil))

(defn- neutral-yaw-degrees [dx dz]
  (- (Math/toDegrees (Math/atan2 (double dx) (double dz)))))

(defn- neutral-pitch-degrees
  [dx dy dz denominator]
  (let [horizontal (case denominator
                     :z-only (Math/sqrt (+ (* (double dz) (double dz))
                                           (* (double dz) (double dz))))
                     (Math/sqrt (+ (* (double dx) (double dx))
                                   (* (double dz) (double dz)))))]
    (- (Math/toDegrees (Math/atan2 (double dy) horizontal)))))

(defn- neutral-angle-delta [a b]
  (let [raw (mod (- (double a) (double b)) 360.0)]
    (if (> raw 180.0) (- raw 360.0) raw)))

(defn- neutral-cone-aabb
  [origin direction distance yaw-span pitch-span]
  ;; The bounded query is deliberately conservative; exact angular filtering
  ;; below is authoritative. A cube around the origin is orientation-safe and
  ;; still has a fixed 128-block maximum, so no candidate can be missed by an
  ;; approximation of the cone's rotated corners.
  (let [[ox oy oz] origin
        radius (double distance)]
    {:min-x (- ox radius) :min-y (- oy radius) :min-z (- oz radius)
     :max-x (+ ox radius) :max-y (+ oy radius) :max-z (+ oz radius)}))

(defn- project-neutral-entity
  [entity projection difficulty-map]
  (let [id (or (:uuid entity) (:entity-id entity))
        type (or (:entity-type entity) (:type entity))
        position {:x (double (or (:x entity) 0.0))
                  :y (double (or (:y entity) 0.0))
                  :z (double (or (:z entity) 0.0))}]
    (reduce (fn [result field]
              (assoc result field
                     (case field
                       :id id :type type :position position
                       :age-ms (long (or (:age-ms entity) 0))
                       :motion-progress (double (or (:motion-progress entity) 0.0))
                       :invulnerable-time (long (or (:invulnerable-time entity) 0))
                       :difficulty (double (or (get difficulty-map type) 0.0))
                       :velocity (or (:velocity entity)
                                     {:x (double (or (:vx entity) 0.0))
                                      :y (double (or (:vy entity) 0.0))
                                      :z (double (or (:vz entity) 0.0))})
                       :owner-id (or (:owner-id entity) (:owner-uuid entity))
                       :item? (boolean (:item? entity))
                       :living? (boolean (:living? entity))
                       :mob? (boolean (:mob? entity))
                       :multipart? (boolean (:multipart? entity))
                       :eye-height (double (or (:eye-height entity)
                                               (:height entity) 0.0))
                       :eye-position {:x (double (or (:x entity) 0.0))
                                      :y (+ (double (or (:y entity) 0.0))
                                            (double (or (:eye-height entity)
                                                        (:height entity) 0.0)))
                                      :z (double (or (:z entity) 0.0))}
                       :behavior-hit? (boolean (:behavior-hit? entity))
                       :explosion-power (:explosion-power entity)
                       nil)))
            {} (or projection [:id :type :position]))))

(defn- entity-select-results
  [{:keys [owner world-id shape filter projection limit]}]
  (let [origin (neutral-point (or (:origin shape) (:center shape)))
        eye-origin (neutral-point (or (:eye-origin shape) (:origin shape) (:center shape)))
        direction (neutral-point (:direction shape))
        radius (double (or (:radius shape) 0.0))
        cone? (= :cone (:type shape))
        distance (double (or (:distance shape) (:range shape) radius))
        limit (max 0 (min 256 (long (or limit 0))))
        owner-id (str owner)
        type-filter (set (or (:entity-types filter) []))
        id-filter (set (map str (or (:entity-ids filter) [])))
        excluded-filter (set (map str (or (:excluded-entity-ids filter) [])))
        entity-owner-filter (some-> (:owner-id filter) str)
        living-filter (when (contains? filter :living?) (boolean (:living? filter)))
        difficulty-map (reduce (fn [result entry]
                                 (if (string? entry)
                                   (let [index (.lastIndexOf ^String entry ":")]
                                     (if (pos? index)
                                       (try (assoc result (subs entry 0 index)
                                                    (Double/parseDouble (subs entry (inc index))))
                                            (catch Throwable _ result))
                                       result))
                                   result)) {} (or (:difficulty-entries filter) []))
        type-match? (fn [entity-type]
                      (or (empty? type-filter)
                          (contains? type-filter entity-type)
                          (some (fn [requested]
                                  (when (and (string? requested)
                                             (string? entity-type))
                                    (let [colon (.lastIndexOf ^String requested ":")
                                          suffix (if (pos? colon)
                                                   (subs requested (inc colon))
                                                   requested)]
                                      (.endsWith ^String entity-type
                                                 (str "." suffix)))))
                                type-filter)))
        valid? (and world-id origin eye-origin (pos? limit)
                    (if cone?
                      (and direction (Double/isFinite distance) (<= 0.0 distance 128.0))
                      (and (Double/isFinite radius) (<= 0.0 radius 64.0))))]
    (when (and valid? (world-effects/available?))
      (let [candidates
            (if cone?
              (let [{:keys [min-x min-y min-z max-x max-y max-z]}
                    (neutral-cone-aabb origin direction distance
                                        (double (or (:yaw-span-degrees shape) 0.0))
                                        (double (or (:pitch-span-degrees shape) 0.0)))]
                (world-effects/find-entities-in-aabb world-id min-x min-y min-z
                                                       max-x max-y max-z))
              (world-effects/find-entities-in-radius world-id
                                                     (nth origin 0) (nth origin 1)
                                                     (nth origin 2) radius))
            filtered (->> candidates
                          (filter map?)
                          (remove #(= owner-id (str (or (:uuid %) (:entity-id %)))))
                          (filter #(let [entity-id (str (or (:uuid %) (:entity-id %)))
                                         entity-type (or (:entity-type %) (:type %))
                                         entity-owner (some-> (or (:owner-id %)
                                                                  (:owner-uuid %)) str)]
                                     (and (type-match? entity-type)
                                          (or (empty? id-filter)
                                              (contains? id-filter entity-id))
                                          (not (contains? excluded-filter entity-id))
                                          (or (nil? entity-owner-filter)
                                              (= entity-owner-filter entity-owner))
                                          (or (nil? living-filter)
                                              (= living-filter (boolean (:living? %))))
                                          (not (:item? %)))))
                          (filter (if-not cone?
                                    identity
                                    (fn [entity]
                                      (let [[ox oy oz] origin
                                            [dx dy dz] direction
                                            player-yaw (neutral-yaw-degrees dx dz)
                                            player-pitch (neutral-pitch-degrees dx dy dz
                                                                                :normal)
                                            tx (- (double (or (:x entity) 0.0)) ox)
                                            ty (- (+ (double (or (:y entity) 0.0))
                                                     (double (or (:eye-height entity)
                                                                 (:height entity) 0.0)))
                                                  (double (second eye-origin)))
                                            tz (- (double (or (:z entity) 0.0)) oz)
                                            target-yaw (neutral-yaw-degrees tx tz)
                                            target-pitch (neutral-pitch-degrees tx ty tz
                                                                                (or (:pitch-denominator shape)
                                                                                    :normal))]
                                        (let [yaw-diff (neutral-angle-delta target-yaw player-yaw)
                                              pitch-diff (- target-pitch player-pitch)
                                              yaw-abs (if (neg? yaw-diff) (- yaw-diff) yaw-diff)
                                              pitch-abs (if (neg? pitch-diff) (- pitch-diff) pitch-diff)]
                                          (and (<= yaw-abs
                                                   (/ (double (or (:yaw-span-degrees shape) 0.0)) 2.0))
                                               (<= pitch-abs
                                                    (double (or (:pitch-span-degrees shape) 0.0)))))))))
                          (map #(project-neutral-entity % projection difficulty-map))
                          (take limit)
                          vec)]
        filtered))))

(defn install-edn-host-capabilities!
  "Link the generic EDN host table to neutral AC platform ports once.

  The handlers exchange only maps and UUIDs.  They do not select abilities or
  contain Arc Gen rules; those remain in EDN.

  Public and called from cn.li.ac.core.init/init, ahead of
  combat-catalog/initialize!, so capabilities are registered before the catalog
  ever loads (Design E precondition R9). It also still runs lazily on first
  dispatch below (compare-and-set! below makes a second call a no-op) as a
  safety net for any other entry path, but that is no longer the only time
  it runs."
  []
  (when (compare-and-set! edn-host-capabilities-installed? false true)
    (try
      ;; Atomic combat capabilities are owned by Combat Core.  AC only links
      ;; the startup registry; mcmod relays each call to platform-src.
      (doseq [[capability handler] (combat-platform/query-handlers)]
        (when-not (contains? (:queries (capabilities/snapshot)) capability)
          (capabilities/register-query! capability handler)))
      (doseq [[capability handler] (combat-platform/action-handlers)]
        (when-not (contains? (:actions (capabilities/snapshot)) capability)
          (capabilities/register-action! capability handler)))
      (when-not (contains? (:queries (capabilities/snapshot)) :owner/snapshot)
        (capabilities/register-query!
         :owner/snapshot
                 (fn [{:keys [owner]} _frame]
                   (let [owner (str owner)
                         position (when (raycast/available?)
                            (raycast/player-position owner))
                         velocity (when (motion-effects/player-motion-available?)
                            (motion-effects/player-velocity owner))
                         look (when (raycast/available?)
                                (raycast/player-look-vector owner))]
             {:position (when (map? position)
                          (select-keys position [:x :y :z :eye-y :world-id]))
              :eye-position (when (map? position)
                             (let [x (double (or (:x position) 0.0))
                                   y (double (or (:y position) 0.0))
                                   z (double (or (:z position) 0.0))
                                   eye-y (double (or (:eye-y position) (+ y 1.62)))]
                               {:x x :y eye-y :z z}))
              :look (or look {:x 0.0 :y 0.0 :z 1.0})
              :velocity (or velocity {:x 0.0 :y 0.0 :z 0.0})
              :can-fly? (motion-effects/player-can-fly? owner)}))))
      (when-not (contains? (:queries (capabilities/snapshot)) :item/held)
        (capabilities/register-query!
         :item/held
         (fn [{:keys [owner source]} _frame]
           (let [stack (when (= :main-hand source) (held-item-at owner))
                 item-id (when (and stack (platform-item/available?))
                           (try (str (platform-item/registry-name
                                      (platform-item/object stack)))
                                (catch Throwable _ nil)))]
             {:present? (boolean stack)
              :supported? (boolean (and stack
                                        (energy/is-energy-item-supported? stack)))
              :item-id item-id
              :block-id item-id
              :source source}))))
      (when-not (contains? (:queries (capabilities/snapshot)) :energy/target)
        (capabilities/register-query!
         :energy/target
         (fn [{:keys [world-id hit]} _frame]
           (energy-target-result world-id hit))))
      (when-not (contains? (:queries (capabilities/snapshot)) :entity/snapshot)
        (capabilities/register-query!
         :entity/snapshot
         (fn [{:keys [world-id entity-id projection]} _frame]
           (when (and world-id entity-id (motion-effects/entity-motion-available?))
             (when-let [position (motion-effects/entity-position
                                  (str world-id) (str entity-id))]
               (let [x (double (or (:x position) 0.0))
                     y (double (or (:y position) 0.0))
                     z (double (or (:z position) 0.0))
                     eye-height (double (or (:eye-height position)
                                            (:height position) 1.62))
                     snapshot {:id (str entity-id)
                               :type (or (:type position) (:entity-type position))
                               :entity-type (or (:entity-type position) (:type position))
                               :x x :y y :z z
                               :eye-height eye-height
                               :eye-position {:x x :y (+ y eye-height) :z z}
                               :alive? (not= false (:alive? position))}]
                 (if (seq projection)
                   (select-keys snapshot
                                (conj (set projection) :alive? :x :y :z
                                      :eye-height :eye-position))
                   snapshot)))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :world/lightning)
        (capabilities/register-action!
         :world/lightning
         (fn [{:keys [owner world-id position visual-only?]}]
           (let [point (if (and (map? position) (vector? (:vec3 position)))
                         (:vec3 position)
                         [(when (map? position) (:x position))
                          (when (map? position) (:y position))
                          (when (map? position) (:z position))])
                 owner-world (geom/world-id-of (str owner))
                 owner-pos (geom/body-pos (str owner))
                 [x y z] point
                 dx (- (double (or x 0.0)) (double (or (:x owner-pos) 0.0)))
                 dy (- (double (or y 0.0)) (double (or (:y owner-pos) 0.0)))
                 dz (- (double (or z 0.0)) (double (or (:z owner-pos) 0.0)))]
             (when (and owner world-id (= world-id owner-world)
                        (boolean visual-only?) owner-pos
                        (= 3 (count point))
                        (every? #(and (number? %) (Double/isFinite (double %))) point)
                        (<= (+ (* dx dx) (* dy dy) (* dz dz)) (* 64.0 64.0))
                        (world-effects/available?))
                        (world-effects/spawn-lightning! world-id (double x) (double y) (double z) true))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :world/explosion)
        (capabilities/register-action!
         :world/explosion
         (fn [{:keys [owner world-id position radius fire? terrain?]}]
           (let [point (if (and (map? position) (vector? (:vec3 position)))
                         (:vec3 position)
                         [(when (map? position) (:x position))
                          (when (map? position) (:y position))
                          (when (map? position) (:z position))])
                 [x y z] point
                 radius (double (or radius 0.0))
                 finite? (fn [value]
                           (and (number? value)
                                (Double/isFinite (double value))))
                 owner-world (geom/world-id-of (str owner))
                 owner-pos (geom/body-pos (str owner))
                 dx (- (double (or x 0.0)) (double (or (:x owner-pos) 0.0)))
                 dy (- (double (or y 0.0)) (double (or (:y owner-pos) 0.0)))
                 dz (- (double (or z 0.0)) (double (or (:z owner-pos) 0.0)))
                 valid? (and owner world-id (= world-id owner-world) owner-pos
                             (every? finite? [x y z radius])
                             (pos? radius) (<= radius 32.0)
                             (<= (+ (* dx dx) (* dy dy) (* dz dz)) (* 128.0 128.0))
                             (world-effects/available?))]
             (if-not valid?
               {:status :rejected :reason :invalid-explosion-request}
               (let [result (world-effects/create-explosion!
                              world-id (double x) (double y) (double z)
                              radius (boolean fire?)
                              {:terrain? (boolean terrain?)
                               :attacker-uuid owner})]
                 {:status (if (not= false result) :applied :failed)}))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/trigger-behavior)
        (capabilities/register-action!
         :entity/trigger-behavior
         (fn [{:keys [world-id entity]}]
           (let [entity-id (if (map? entity)
                             (or (:id entity) (:uuid entity) (:entity-id entity))
                             entity)]
             (when (and world-id entity-id (world-effects/available?))
               {:status (if (world-effects/trigger-behavior-hit!
                             (str world-id) (str entity-id))
                          :applied
                          :failed)})))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/mark)
        (capabilities/register-action!
         :entity/mark
         (fn [{:keys [owner target mark-type]}]
           (when (and owner target mark-type)
             (when-let [mark-target! (requiring-resolve
                                      'cn.li.ac.content.ability.meltdowner.damage-helper/mark-target!)]
               (mark-target! owner target)
                {:status :applied})))))
      (when-not (contains? (:actions (capabilities/snapshot)) :energy/charge)
        (capabilities/register-action!
         :energy/charge
         (fn [{:keys [owner world-id mode target amount]}]
           (let [amount (double (or amount 0.0))
                 applied? (and (pos? amount)
                               (Double/isFinite amount)
                               (case mode
                                 :item (let [stack (held-item-at owner)]
                                         (when (and stack
                                                    (energy/is-energy-item-supported? stack))
                                           (< (double (energy/charge-energy-to-item
                                                       stack amount false)) amount)))
                                 :block (let [tile (resolve-energy-tile
                                                    world-id (:block-pos target))]
                                          (boolean
                                           (when tile
                                             (cond
                                               (energy/is-node-supported? tile)
                                               (< (double (energy/charge-node tile amount true)) amount)
                                               (energy/is-receiver-supported? tile)
                                               (< (double (energy/charge-receiver tile amount)) amount)
                                               :else false))))
                                 false))]
             {:status (if applied? :applied :failed)}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :owner/can-fly)
        (capabilities/register-action!
         :owner/can-fly
         (fn [{:keys [owner enabled?]}]
           (let [valid? (and owner (motion-effects/player-motion-available?))
                 applied? (and valid?
                               (motion-effects/set-player-can-fly!
                                (str owner) (boolean enabled?)))]
             {:status (if applied? :applied :failed)}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :motion/flight)
        (capabilities/register-action!
         :motion/flight
         (fn [{:keys [owner world-id direction speed acceleration
                      hover-near-ground-velocity hover-air-velocity
                      near-ground-distance near-ground-eye-height
                      reset-fall-damage?]}]
           (let [owner (some-> owner str)
                 position (when (and owner (raycast/available?))
                            (raycast/player-position owner))
                 current (or (when (and owner (motion-effects/player-motion-available?))
                               (motion-effects/player-velocity owner))
                             {:x 0.0 :y 0.0 :z 0.0})
                 point (cond
                         (and (map? direction) (vector? (:vec3 direction))) (:vec3 direction)
                         (map? direction) [(:x direction) (:y direction) (:z direction)]
                         :else nil)
                 finite? (fn [v] (and (number? v) (Double/isFinite (double v))))
                 [dx dy dz] (mapv #(double (or % 0.0)) (or point [0.0 0.0 0.0]))
                 len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                 moving? (> len 1.0e-6)
                 nx (if moving? (/ dx len) 0.0)
                 ny (if moving? (/ dy len) 0.0)
                 nz (if moving? (/ dz len) 0.0)
                 speed (double (or speed 0.0))
                 acceleration (double (or acceleration 0.0))
                 near-ground-distance (double (or near-ground-distance 0.8))
                 eye-height (double (or near-ground-eye-height 0.5))
                 hover-near (double (or hover-near-ground-velocity 0.1))
                 hover-air (double (or hover-air-velocity 0.078))
                 cx (double (or (:x current) 0.0))
                 cy (double (or (:y current) 0.0))
                 cz (double (or (:z current) 0.0))
                 approach (fn [from to]
                           (let [delta (- to from)
                                 step (min (Math/abs (double delta)) acceleration)]
                             (+ from (if (neg? delta) (- step) step))))
                 valid? (and owner position (motion-effects/player-motion-available?)
                             (every? finite? [dx dy dz speed acceleration
                                              near-ground-distance eye-height hover-near hover-air])
                             (<= 0.0 speed 32.0) (<= 0.0 acceleration 4.0)
                             (pos? near-ground-distance) (<= near-ground-distance 8.0))]
             (if-not valid?
               {:status :rejected :reason :invalid-flight-request}
               (let [near-ground? (and (raycast/available?)
                                       (raycast/raycast-blocks
                                        (str world-id)
                                        (double (:x position))
                                        (+ (double (:y position)) eye-height)
                                        (double (:z position))
                                        0.0 -1.0 0.0 near-ground-distance))
                     tx (if moving? (approach cx (* nx speed)) cx)
                     ty (if moving? (approach cy (* ny speed))
                          (if near-ground? hover-near (+ cy hover-air)))
                     tz (if moving? (approach cz (* nz speed)) cz)
                     dismounted? (or (not moving?)
                                     (motion-effects/dismount-riding! owner))
                     applied? (and dismounted?
                                   (motion-effects/set-player-velocity! owner tx ty tz))]
                 (when (and applied? (not= false reset-fall-damage?))
                   (motion-effects/reset-fall-damage! owner))
                 {:status (if applied? :applied :failed)
                  :velocity {:x tx :y ty :z tz}}))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :motion/velocity)
        (capabilities/register-action!
         :motion/velocity
          (fn [{:keys [owner velocity dismount? reset-fall-damage?]}]
           (let [point (cond
                         (and (map? velocity) (vector? (:vec3 velocity)))
                         (:vec3 velocity)
                         (map? velocity)
                         [(:x velocity) (:y velocity) (:z velocity)]
                         (and (vector? velocity) (= 3 (count velocity)))
                         velocity
                         :else nil)
                 finite? (fn [value]
                           (and (number? value)
                                (Double/isFinite (double value))))
                 valid? (and owner point
                             (= 3 (count point))
                             (every? finite? point)
                             (motion-effects/player-motion-available?))
                 [x y z] (mapv double point)
                  owner-id (str owner)
                  dismounted? (or (not (true? dismount?))
                                  (motion-effects/dismount-riding! owner-id))
                  applied? (and valid? dismounted?
                                (motion-effects/set-player-velocity!
                                 owner-id x y z))]
              (when (and applied? (true? reset-fall-damage?))
                (motion-effects/reset-fall-damage! owner-id))
             {:status (if applied? :applied :failed)
              :velocity {:x x :y y :z z}}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/radial-impulse)
        (capabilities/register-action!
         :entity/radial-impulse
         (fn [{:keys [owner world-id center radius speed-min speed-max seed]}]
           (let [point (cond
                         (and (map? center) (vector? (:vec3 center))) (:vec3 center)
                         (map? center) [(:x center) (:y center) (:z center)]
                         :else nil)
                 finite? (fn [v] (and (number? v) (Double/isFinite (double v))))
                 [x y z] (mapv #(double (or % 0.0)) (or point [0.0 0.0 0.0]))
                 radius (double (or radius 0.0))
                 speed-min (double (or speed-min 0.0))
                 speed-max (double (or speed-max speed-min))
                 valid? (and owner world-id point (world-effects/available?)
                             (motion-effects/entity-motion-available?)
                             (every? finite? [x y z radius speed-min speed-max])
                             (<= 0.0 radius 32.0) (<= 0.0 speed-min speed-max 32.0))]
             (if-not valid?
               {:status :rejected :reason :invalid-radial-impulse-request}
               (let [entities (take 256 (world-effects/find-entities-in-radius
                                         (str world-id) x y z radius))
                     seed (long (or seed 0))
                     result (loop [xs (seq entities) index 0 hits 0]
                              (if-let [entity (first xs)]
                                (let [id (or (:id entity) (:uuid entity))
                                      ex (double (or (:x entity) x))
                                      ey (+ (double (or (:y entity) y))
                                            (double (or (:eye-height entity) 0.0)))
                                      ez (double (or (:z entity) z))
                                      vx (- ex x) vy (- ey y) vz (- ez z)
                                      length (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz)))
                                      ^long next-rng (seeded-rng/next-long
                                                      (unchecked-add seed index))]
                                  (if (or (nil? id) (= (str id) (str owner))
                                          (<= length 1.0e-6))
                                    (recur (next xs) (inc index) hits)
                                    (let [magnitude (seeded-rng/uniform next-rng speed-min speed-max)
                                          applied? (motion-effects/set-entity-velocity!
                                                    (str world-id) (str id)
                                                    (* (/ vx length) magnitude)
                                                    (* (/ vy length) magnitude)
                                                    (* (/ vz length) magnitude))]
                                      (recur (next xs) (inc index)
                                             (if applied? (inc hits) hits)))))
                                hits))]
                 {:status :applied :hits result}))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :block/random-break)
        (capabilities/register-action!
         :block/random-break
         (fn [{:keys [owner world-id origin attempts radius hardness-max seed drop?]}]
           (let [point (cond
                         (and (map? origin) (vector? (:vec3 origin))) (:vec3 origin)
                         (map? origin) [(:x origin) (:y origin) (:z origin)]
                         :else nil)
                 finite? (fn [v] (and (number? v) (Double/isFinite (double v))))
                 [x y z] (mapv #(double (or % 0.0)) (or point [0.0 0.0 0.0]))
                 attempts (long (or attempts 0))
                 radius (double (or radius 0.0))
                 hardness-max (double (or hardness-max 0.0))
                 valid? (and owner world-id point (block-manipulation/available?)
                             (every? finite? [x y z radius hardness-max])
                             (<= 0 attempts 256) (<= 0.0 radius 32.0)
                             (<= 0.0 hardness-max 64.0))]
             (if-not valid?
               {:status :rejected :reason :invalid-random-break-request}
               (let [seed (long (or seed 0))
                 broken (loop [index 0 total 0]
                          (if (>= index attempts)
                            total
                                (let [rng (seeded-rng/next-long (unchecked-add seed index))
                                      rx (long (Math/floor (seeded-rng/uniform rng (- radius) radius)))
                                      ry (long (Math/floor (seeded-rng/uniform (seeded-rng/next-long rng)
                                                                              (- radius) radius)))
                                      rz (long (Math/floor (seeded-rng/uniform (seeded-rng/next-long
                                                                                (seeded-rng/next-long rng))
                                                                              (- radius) radius)))
                                      bx (long (Math/floor (+ x rx)))
                                      by (long (Math/floor (+ y ry)))
                                      bz (long (Math/floor (+ z rz)))
                                      hardness (double (or (block-manipulation/get-block-hardness
                                                           (str world-id) bx by bz)
                                                          -1.0))
                                      allowed? (and (>= hardness 0.0) (<= hardness hardness-max)
                                                    (block-manipulation/can-break-block?
                                                     (str owner) (str world-id) bx by bz))
                                      did-break? (and allowed?
                                                       (not= false
                                                             (block-manipulation/break-block!
                                                              (str owner) (str world-id) bx by bz
                                                              (not= false drop?))))]
                                  (recur (inc index)
                                         (if did-break? (inc total) total)))))]
                 {:status :applied :broken broken}))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/teleport)
        (capabilities/register-action!
         :entity/teleport
         (fn [{:keys [owner world-id target position dismount? reset-fall-damage?]}]
           (let [point (or position target)
                 [x y z] (if (and (map? point) (vector? (:vec3 point)))
                           (:vec3 point)
                           [(when (map? point) (:x point))
                            (when (map? point) (:y point))
                            (when (map? point) (:z point))])
                 finite? (fn [value]
                           (and (number? value)
                                (Double/isFinite (double value))))
                 valid? (and owner world-id
                             (every? finite? [x y z])
                             (teleportation/available?))]
             (if-not valid?
               {:status :rejected :reason :invalid-teleport-request}
               (do
                 (when dismount?
                   (motion-effects/dismount-riding! (str owner)))
                 (let [success (teleportation/teleport-player!
                                (str owner) (str world-id)
                                (double x) (double y) (double z))]
                   (when (and success reset-fall-damage?)
                     (motion-effects/reset-fall-damage! (str owner)))
                   {:status (if success :applied :failed)
                    :position {:x (double x) :y (double y) :z (double z)}})))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/reset-fall-damage)
        (capabilities/register-action!
         :entity/reset-fall-damage
         (fn [{:keys [owner target]}]
           (let [entity-id (str (or target owner))]
             (if (and (seq entity-id) (motion-effects/entity-motion-available?))
               (do (motion-effects/reset-fall-damage! entity-id)
                   {:status :applied})
               {:status :rejected :reason :entity-motion-port-missing})))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/status)
        (capabilities/register-action!
         :entity/status
         (fn [{:keys [world-id target status-id duration-ticks amplifier]}]
           (when target
             (if (= :powered-creeper status-id)
               (motion-effects/power-creeper! world-id target)
               (potion-effects/apply-effect!
                target status-id (int (max 0 (min 1200 (long duration-ticks))))
                (int (max 0 (min 255 (long amplifier))))))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/spawn)
        (capabilities/register-action!
         :entity/spawn
         (fn [{:keys [world-id owner entity-type position velocity life-ticks]}]
           (if (and world-id owner (string? entity-type)
                    (world-effects/available?))
             (let [entity-id (world-effects/spawn-entity!
                               world-id owner entity-type position velocity life-ticks)
                   applied? (boolean entity-id)]
               (when (and applied? (not (true? entity-id)))
                 (swap! spawned-entity-ids* update-in
                        [(str world-id) (str owner) entity-type]
                        (fnil conj []) entity-id))
               {:status (if applied? :applied :failed)
                :entity-id (when (not (true? entity-id)) entity-id)})
             {:status :rejected :reason :invalid-entity-spawn}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/discard)
        (capabilities/register-action!
         :entity/discard
         (fn [{:keys [world-id entity]}]
           (let [entity-id (or (:id entity) (:uuid entity) (:entity-id entity))
                 owner (:owner entity)
                 entity-type (:entity-type entity)
                 tracked (when (and world-id owner entity-type)
                           (get-in @spawned-entity-ids*
                                   [(str world-id) (str owner) entity-type]))
                 ids (if entity-id [entity-id] tracked)
                 discarded (if (and world-id (world-effects/available?))
                             (count (filter #(world-effects/discard-entity-by-uuid!
                                             world-id %)
                                            ids))
                             0)]
             (when (and owner entity-type)
               (swap! spawned-entity-ids* update-in
                      [(str world-id) (str owner) entity-type]
                      (constantly [])))
             {:status (if (pos? discarded) :applied :failed)
              :discarded discarded}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/configure)
        (capabilities/register-action!
         :entity/configure
         (fn [{:keys [world-id entity block-id place-when-collide? position]}]
           (let [entity-id (if (map? entity)
                             (or (:id entity) (:uuid entity) (:entity-id entity))
                             entity)
                 point (cond
                         (and (map? position) (vector? (:vec3 position))) (:vec3 position)
                         (map? position) [(:x position) (:y position) (:z position)]
                         (vector? position) position
                         :else nil)
                 configured? (and world-id entity-id
                                  (motion-effects/entity-motion-available?))
                 block-ok? (or (nil? block-id)
                               (motion-effects/set-block-body-block-id!
                                (str world-id) (str entity-id) (str block-id)))
                 place-ok? (or (nil? place-when-collide?)
                               (motion-effects/set-block-body-place-when-collide!
                                (str world-id) (str entity-id)
                                (boolean place-when-collide?)))
                 pos-ok? (or (nil? point)
                             (and (= 3 (count point))
                                  (every? number? point)
                                  (apply motion-effects/set-entity-position!
                                         (str world-id) (str entity-id)
                                         (map double point))))]
             {:status (if (and configured? block-ok? place-ok? pos-ok?)
                        :applied :failed)}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :motion/entity-velocity)
        (capabilities/register-action!
         :motion/entity-velocity
         (fn [{:keys [world-id target velocity]}]
           (let [entity-id (if (map? target)
                             (or (:id target) (:uuid target) (:entity-id target))
                             target)
                 point (cond
                         (and (map? velocity) (vector? (:vec3 velocity))) (:vec3 velocity)
                         (map? velocity) [(:x velocity) (:y velocity) (:z velocity)]
                         (vector? velocity) velocity
                         :else nil)
                 finite? (fn [v] (and (number? v) (Double/isFinite (double v))))
                 valid? (and world-id entity-id (= 3 (count (or point [])))
                             (every? finite? point)
                             (motion-effects/entity-motion-available?))]
             {:status (if (and valid?
                               (apply motion-effects/set-entity-velocity!
                                      (str world-id) (str entity-id)
                                      (map double point)))
                        :applied :failed)}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :projectile/schedule-beam)
        (capabilities/register-action!
         :projectile/schedule-beam
         (fn [{:keys [owner world-id origin destination damage damage-type delay-ticks]}]
           (if (and owner world-id (map? origin) (map? destination)
                    (number? damage) (Double/isFinite (double damage)))
             (do
               ((requiring-resolve
                 'cn.li.ac.ability.service.delayed-projectiles/schedule-beam!)
                {:owner owner :world-id world-id :origin origin
                 :destination destination :damage (double damage)
                 :damage-type (or damage-type :generic)
                 :delay-ticks (long (max 1 (or delay-ticks 1)))})
               {:status :scheduled})
             {:status :rejected :reason :invalid-beam-request}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :projectile/redirect)
        (capabilities/register-action!
         :projectile/redirect
         (fn [{:keys [owner world-id entity target-position velocity
                      replacement-types]}]
           (let [entity-id (or (:id entity) (:uuid entity) (:entity-id entity))
                 entity-type (or (:type entity) (:entity-type entity))
                 target (or target-position {})
                 velocity (or velocity {})
                 [tx ty tz] (if (vector? (:vec3 target))
                              (:vec3 target)
                              [(:x target) (:y target) (:z target)])
                 [vx vy vz] (if (vector? (:vec3 velocity))
                              (:vec3 velocity)
                              [(:x velocity) (:y velocity) (:z velocity)])
                 finite? (fn [v] (and (number? v) (Double/isFinite (double v))))
                 valid? (and owner world-id entity-id
                             (every? finite? [tx ty tz vx vy vz]))]
             (if-not valid?
               {:status :rejected :reason :invalid-projectile-redirect}
               (let [replacement? (contains? (set (or replacement-types []))
                                              entity-type)
                     spawn-result (when replacement?
                                    (try
                                      (world-effects/spawn-projectile!
                                       world-id
                                       {:entity-id entity-type
                                        :x (double (or (:x entity) 0.0))
                                        :y (double (or (:y entity) 0.0))
                                        :z (double (or (:z entity) 0.0))
                                        :vx (double vx) :vy (double vy) :vz (double vz)
                                        :owner-uuid (:owner-id entity)
                                        :explosion-power (:explosion-power entity)})
                                      (catch Throwable _ {:success? false})))]
                 (if (and replacement? (:success? spawn-result))
                   (do
                     (when (motion-effects/entity-motion-available?)
                       (motion-effects/discard-entity! world-id entity-id))
                     {:status :applied :replacement-id (:uuid spawn-result)})
                   (if (motion-effects/entity-motion-available?)
                     (do
                       (motion-effects/set-entity-velocity!
                        world-id entity-id (double vx) (double vy) (double vz))
                       {:status :applied :entity-id entity-id})
                     {:status :unhandled :reason :entity-motion-port-missing}))))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :resource/enforce-floor)
        (capabilities/register-action!
         :resource/enforce-floor
         (fn [{:keys [owner resource minimum]}]
           (if (and owner (= :overload resource) (number? minimum)
                    (Double/isFinite (double minimum)))
             (let [result (command-runtime/run-commands-in-session!
                           (server-session-id) (str owner)
                           [{:command :enforce-overload-floor
                             :floor-value (double minimum)}])]
               {:status (if (:success? result) :applied :failed)})
             {:status :rejected :reason :invalid-resource-floor}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :resource/add)
        (capabilities/register-action!
         :resource/add
         (fn [{:keys [owner resource amount]}]
           (let [amount (double (or amount 0.0))]
             (if (and owner (= :overload resource)
                      (Double/isFinite amount) (pos? amount))
               (let [result (command-runtime/run-command-in-session!
                              (server-session-id) (str owner)
                              {:command :consume-resource
                               :overload amount :cp 0.0 :creative? false})]
                 {:status (if (:success? result) :applied :failed)})
               {:status :rejected :reason :invalid-resource-add})))))
      (catch Throwable _
        ;; A loader may freeze the registry before AC content boots.  Leave the
        ;; registry state authoritative; missing ports surface as :unhandled.
        (reset! edn-host-capabilities-installed? false)))
  (capabilities/snapshot)))

(defn- execute-combat-intent!
  "Delegate the complete EDN lifecycle operation to Combat Core.

   AC supplies only session/state/platform callbacks; it does not inspect
   component trees or execute the VM."
  [owner intent]
  (combat-skill-runtime/dispatch!
   {:catalog (combat-catalog/catalog)
    :owner owner
    :intent intent
    :now-tick-fn #(long (or @last-known-tick* 0))
    :seed-fn generate-activation-seed
    :owner-view-fn owner-state
    :activation-context-fn activation-context
    :caster-facade-fn caster-facade
    :session-port
    {:current combat-sessions/session
     :resolve-slot-fn resolve-slot
     :start! combat-sessions/start!
     :context combat-sessions/context-for
     :apply-actions! (fn [owner actions]
                       (combat-sessions/apply-actions! owner actions))
     :remove! combat-sessions/remove!}}))

(defn dispatch-intent! [owner intent]
  ;; The migration table is authoritative at the server boundary.  Pending
  ;; skills never reach the legacy engine and therefore have no compatibility
  ;; fallback; once their EDN catalog entry is migrated this gate naturally
  ;; opens without changing the core runtime.
  (let [;; Slot resolution is server-owned preset data, never a client
        ;; ability/event mapping.  The resolved id is then checked against the
        ;; migrated EDN catalog before execution.
        ability-id (edn-ability-id owner intent)]
    (if-not (combat-catalog/available? ability-id)
      {:schema-version 2
        :status :rejected
        :reason :ability-not-migrated
        :feedback [{:type :ability-not-migrated
                    :ability-id ability-id
                    :status (combat-catalog/migration-status ability-id)}]}
      (do
        (install-edn-host-capabilities!)
        (execute-combat-intent! owner intent)))))

(defn dispatch-trigger!
  "Dispatch a server-resolved external trigger from the EDN trigger index.

  The trigger map is produced by `combat-catalog/resolve-trigger`; clients never
  provide ability/event mappings." 
  [owner trigger context]
  (when (and (map? trigger) (:ability trigger) (:event trigger))
    ;; An external event is a terminal interruption for the owner session.
    ;; Run the generic EDN abort phase first so session-scoped VFX and other
    ;; cleanup actions are finalized through the same commit boundary.
    (when-let [session (combat-sessions/session owner)]
      (let [abort-result (execute-combat-intent!
                          owner
                          {:op :abort
                           :action :abort
                           :ability-id (:ability-id session)
                           :context (:context session)
                           :parameter-snapshot (:parameter-snapshot session)
                           :activation-seed (:activation-seed session)})]
        (when (= :accepted (:status abort-result))
          (publish-result! (finalize-result! owner abort-result)))))
    (dispatch-intent! owner
                      {:op :event
                       :action :event
                       :ability-id (:ability trigger)
                       :event (:event trigger)
                       :context context})))
(defn- handle-neutral-domain-event!
  "Apply the two generic domain events emitted by the migrated Arc recipe.

  The event contains only a bounded impact fact and probabilities from the
  activation snapshot.  We re-read the target block immediately before a
  mutation, so a stale raycast cannot overwrite a changed world block."
  [event]
  (case (:type event)
    :achievement/trigger
    (let [payload (:payload event)]
      (when (and (map? payload) (:owner event) (:id payload))
        (achievement-dispatcher/trigger-custom-event!
         (str (:owner event)) (str (:id payload))))
      {:status :applied :type (:type event)})

    :world/block-impact
    (let [{:keys [world-id position block-position water? ignite-probability
                  fishing-probability fishing-exp-threshold skill-exp seed]} (:payload event)
          point (cond
                  (vector? position) position
                  (map? position) [(:x position) (:y position) (:z position)]
                  :else nil)
          block-point (cond
                        (vector? block-position) block-position
                        (map? block-position) [(:x block-position)
                                               (:y block-position)
                                               (:z block-position)]
                        :else nil)
          finite-point? (fn [p]
                          (and (vector? p) (= 3 (count p))
                               (every? #(and (number? %) (Double/isFinite (double %))) p)))
          seed (long (or seed 0))
          fish? (and water? (> (double (or skill-exp 0.0))
                               (double (or fishing-exp-threshold 1.0)))
                     (< (seeded-rng/unit-double seed)
                        (double (or fishing-probability 0.0))))
          ignite? (and (not water?)
                       (< (seeded-rng/unit-double (seeded-rng/next-long seed))
                          (double (or ignite-probability 0.0))))]
      (cond
        (not (and (string? world-id) (finite-point? point)
                  (finite-point? block-point)))
        {:status :rejected :reason :invalid-impact-fact}

        fish?
        (if (and (server-bridge/server-bridge-available?)
                 (<= (Math/abs (- (double (nth point 0))
                                  (double (nth block-point 0)))) 1.0)
                 (<= (Math/abs (- (double (nth point 1))
                                  (double (nth block-point 1)))) 1.0)
                 (<= (Math/abs (- (double (nth point 2))
                                  (double (nth block-point 2)))) 1.0))
          (do (server-bridge/spawn-item-stack-at!
               world-id (double (nth point 0)) (double (nth point 1))
               (double (nth point 2)) "minecraft:cooked_cod" 1)
              {:status :applied :operation :spawn-item})
          {:status :unhandled :reason :missing-item-spawn-port})

        ignite?
        (let [[x y z] (mapv #(int (Math/floor (double %))) block-point)
              current (when (block-manipulation/available?)
                        (block-manipulation/get-block world-id x (inc y) z))]
          (if (and (block-manipulation/available?)
                   (or (nil? current) (= "minecraft:air" current)))
            (do (block-manipulation/set-block! world-id x (inc y) z "minecraft:fire")
                {:status :applied :operation :ignite})
            {:status :rejected :reason :impact-target-not-air}))

        :else {:status :applied :operation :none}))

    nil))

(defn dispatch-domain-event! [event]
  (or (handle-neutral-domain-event! event)
      (combat/dispatch-domain-event! (engine) event)))

(defn dispatch-result-domain-events!
  "Dispatch explicit domain events from one CombatResult.

   Query trace entries are intentionally ignored.  The caller controls when
   this seam is invoked so ordering with StatePatch and WorldEffect commits is
   explicit at the application boundary."
  [owner result]
  (reduce (fn [results event]
            (if (and (map? event) (not= :query (:type event)))
              (conj results
                    (dispatch-domain-event!
                     (assoc event :owner (or (:owner event) owner))))
              results))
          []
          (:events result)))

(defn- apply-combat-damage-reactions
  "Delegate declarative damage reactions to Combat Core."
  [request boundary]
  (combat-reactions/apply!
   request
   {:reactions (vals (get-in (combat-catalog/catalog) [:combat :abilities]))
    :session-fn #(combat-sessions/session (str %))
    :state-fn owner-state
    :precheck? (:precheck? boundary)
    :tunables-fn
    (fn [ability-id session state]
      (let [ability (get-in (combat-catalog/catalog)
                            [:combat :abilities ability-id])
            skill-exp (double (or (get-in state
                                          [:ability-data :skill-exps ability-id])
                                  0.0))]
                 (combat-skill-runtime/materialize-tunables ability skill-exp)))}
   ))

(defn process-damage-request!
  "Authoritative damage interception boundary for platform adapters.

   The old mutable damage-handler registry is not consulted. Combat Core
   returns the transformed neutral request; the platform writes only the
   resulting numeric amount back to its event."
  [player-id attacker-id original-damage damage-source]
  (let [request (apply-combat-damage-reactions
                (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :intercepted}
                  :metadata {:damage-source damage-source
                             :world-id (or (:world-id damage-source)
                                           (some-> (raycast/player-position
                                                    (str player-id))
                                                   :world-id))
                             :attacker-front? (attacker-front?
                                               player-id attacker-id damage-source)}})
                {})]
    ;; Damage interception is the live boundary: Combat Core owns the
    ;; reduction decision, while AC remains the single writer for player
    ;; resources/cooldowns.  Apply only the neutral patch returned by the
    ;; pipeline; never reconstruct costs in the platform hook.
    (when (and (not (:cancelled? request))
               (seq (:state-patch request)))
      (commit-state-patch! player-id (:state-patch request)))
    (when (and (not (:cancelled? request))
               (seq (:session-patch request)))
      (combat-sessions/apply-actions!
       player-id [{:type :session-patch :entries (:session-patch request)}]))
    (when (and (not (:cancelled? request))
               (damage-output? request))
      (execute-damage-effects! player-id request))
    (when (and (not (:cancelled? request)) (seq (:events request)))
      (dispatch-result-domain-events! player-id request))
    (if (:cancelled? request)
      0.0
      (double (:base request)))))

(defn process-attack-precheck!
  "Route attack prechecks through the authoritative DamageRequest pipeline.

   The removed mutable cancel/precheck registries have no replacement hook;
   combat nodes can cancel through the same deterministic request pipeline."
  [player-id attacker-id original-damage damage-source]
  (let [request (apply-combat-damage-reactions
                (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :attack-precheck}
                  :metadata {:damage-source damage-source
                             :world-id (or (:world-id damage-source)
                                           (some-> (raycast/player-position
                                                    (str player-id))
                                                   :world-id))
                             :attacker-front? (attacker-front?
                                               player-id attacker-id damage-source)}})
                {:precheck? true})]
    {:cancelled? (boolean (:cancelled? request))
     :request request}))

(defn apply-attack-precheck!
  "Apply validated Combat Core reflection output before native hurt.

   The platform calls this single boundary before cancellation. Ordinary
   requests stay pure and continue to live damage; reflection output is
   committed and executed here exactly once."
  [player-id attacker-id original-damage damage-source]
  (let [{:keys [request]} (process-attack-precheck!
                           player-id attacker-id original-damage damage-source)
        damage? (damage-output? request)
        patch? (or (seq (:state-patch request))
                   (seq (:session-patch request)))]
    ;; A fully absorbed hit has no world-effect payload, but it still owns the
    ;; resource/session patches and must cancel the native hit.  Partial
    ;; absorbs are intentionally deferred by the reaction to the amount
    ;; modifier, avoiding a second payment on the same attack.
    (when (or damage? patch?)
      (commit-state-patch! player-id (:state-patch request))
      (when (seq (:session-patch request))
        (combat-sessions/apply-actions!
         player-id [{:type :session-patch :entries (:session-patch request)}]))
      (when damage?
        (execute-damage-effects! player-id request)
        (dispatch-result-domain-events! player-id request)))
    (boolean (or (:cancelled? request) damage?))))

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

(defn finalize-result!
  "Apply one accepted result at the AC composition boundary.

   World effects execute before explicit domain-event reduction.  Both
   acknowledgements remain attached to the immutable result for publication
   and diagnostics."
  [owner result]
  (let [result (if (and (= 2 (:schema-version result))
                        (= :accepted (:status result)))
                 (let [actions (vec (:actions result))
                       patch-results (commit-edn-owner-patches! owner actions)
                       action-results
                 (combat-skill-runtime/commit-actions!
                        owner
                        (vec (remove #(#{:owner-patch :session-patch}
                                       (:type %)) actions))
                        (:actions (capabilities/snapshot)))]
                   (assoc result
                          :patch-results (vec patch-results)
                          :action-results action-results))
                 result)
        result (execute-world-effects! owner result)
        domain-results (if (= :accepted (:status result))
                         (dispatch-result-domain-events! owner result)
                         [])]
    (assoc result :domain-event-results (vec domain-results))))
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
  (reset! last-known-tick* (long tick))
  (let [edn-results
        (mapv (fn [[owner session]]
                (execute-combat-intent!
                 owner
                 {:op :pulse
                  :action :pulse
                  :ability-id (:ability-id session)
                  :server-tick (long tick)}))
              (combat-sessions/tick! tick))
        ]
    (mapv (fn [result]
            ;; Session pulses produce authoritative patches before effects
            ;; and publication, exactly like start/release intents.
            (when (= :accepted (:status result))
              (commit-state-patch! (:owner result) (:state-patch result)))
            (publish-result! (finalize-result! (:owner result) result)))
          edn-results)))
(defn abort-owner! [owner]
  (combat-sessions/remove! owner)
  nil)
(defn snapshot-owner [owner]
  {:combat-session (combat-sessions/session owner)})

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! world-effect-handler* nil)
  (reset! result-sink* nil)
  (reset! last-known-tick* 0)
  (combat-sessions/reset-for-test!)
  nil)
