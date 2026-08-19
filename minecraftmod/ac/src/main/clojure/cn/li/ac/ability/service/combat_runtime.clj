(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as combat]
            [cn.li.combat.vm :as combat-vm]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.ac.ability.service.radiation-marks :as radiation-marks]
            [cn.li.ac.ability.service.light-shield-state :as light-shield-state]
            [cn.li.ac.ability.service.edn-catalog :as edn-catalog]
            [cn.li.ac.ability.service.edn-execution :as edn-execution]
            [cn.li.ac.ability.service.edn-sessions :as edn-sessions]
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
            [cn.li.mcmod.server.platform-bridge :as server-bridge]
            [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]
            [cn.li.ac.content.ability.teleporter.location-teleport :as location-teleport]
            [cn.li.ac.content.ability.teleporter.mark-teleport-dest :as mark-teleport-dest]
            [cn.li.ac.content.ability.teleporter.penetrate-dest :as penetrate-dest]
            [cn.li.ac.content.ability.teleporter.flashing-dest :as flashing-dest]
            [cn.li.ac.energy.operations :as energy]
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
(declare owner-state resolve-slot execute-world-effects! finalize-result! publish-result!)

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

    :light-shield-start
    (assoc-in state [:light-shields (str (:owner event))]
              (light-shield-state/start (:overload-floor event)))

    :light-shield-tick
    (update-in state [:light-shields (str (:owner event))]
               light-shield-state/tick)

    :light-shield-absorb
    (update-in state [:light-shields (str (:owner event))]
               (fn [shield]
                 (when shield
                   (assoc shield :last-absorb-tick (long (:tick event))))))

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

(defn- accepted-metal-block-ids
  "Union of the configured normal + weak metal block ids (targeting.metal.*),
   the same allow-list the pre-combat-core MagManip/MagMovement defskills
   used via ability-config/is-metal-block?. Weak metal is accepted
   unconditionally (no exp gate) -- see targeting.weak-metal-exp-threshold's
   own comment in skill_config/electromaster.clj: the original effective
   check already accepted weak metal at every exp level."
  []
  (vec (distinct (concat (ability-config/get-normal-metal-blocks)
                         (ability-config/get-weak-metal-blocks)))))

;; MagManip's grab->throw span crosses two independent query dispatches
;; (:start and :release, separately invoked as the player holds a key across
;; up to 200 ticks) with no combat-core session-patch primitive able to carry
;; a query result between them (:session-patch entries only resolve scale/
;; session/literal expressions, never a prior step's :refs). This owner-keyed
;; atom is the same technique already used for mine-ray/electron-missile's
;; multi-tick state, just kept on the AC/query side instead of the platform/
;; world-effect side since grabbing needs no Minecraft entity API.
(defonce ^:private mag-manip-held* (atom {}))

(defn- raycast-metal-block
  "Raycast for the first metal block (per accepted-metal-block-ids) along
   owner's look vector within `range`. Returns {:world-id :x :y :z :block-id
   :hardness} or nil."
  [owner range]
  (when (raycast/available?)
    (let [world-id (geom/world-id-of owner)
          eye (geom/eye-pos owner)
          look (raycast/player-look-vector owner)
          accepted (accepted-metal-block-ids)]
      (when (and look (seq accepted))
        (when-let [hit (raycast/raycast-blocks-matching
                        world-id (:x eye) (:y eye) (:z eye)
                        (double (or (:x look) 0.0))
                        (double (or (:y look) 0.0))
                        (double (or (:z look) 1.0))
                        (double range)
                        accepted)]
          (let [bx (long (or (:x hit) 0))
                by (long (or (:y hit) 0))
                bz (long (or (:z hit) 0))
                block-id (or (:block-id hit)
                             (block-manipulation/get-block world-id bx by bz))
                hardness (block-manipulation/get-block-hardness world-id bx by bz)]
            (when (and (string? block-id)
                       (ability-config/is-metal-block? block-id)
                       (number? hardness)
                       (not (neg? (double hardness))))
              {:world-id world-id :x bx :y by :z bz
               :block-id block-id :hardness hardness})))))))

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

;; :runtime-interop's :get-block-entity-at is a still-installed, still-used
;; neutral op (cn.li.ac.item.developer-portable-energy calls the same
;; adapter for its own energy access) -- world-id/x/y/z in, no live level
;; object required. cn.li.ac.energy.operations (this file's `energy` alias)
;; is AC-layer, so charge-energy's world-effect case below has to stay in
;; this file rather than delegating to a platform-src execute-*! like every
;; other world-effect this session: platform code must never require AC
;; namespaces, and ac.energy.operations is squarely AC.
(defn- block-entity-at
  [world-id x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-block-entity-at world-id x y z)))

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
   {:priority 80
    :provider-id :academy/base
    :ability-id :light-shield
    :node-id :damage-absorption
    :run (fn [request context]
           (let [target (str (:target request))
                 shield (get-in context [:domain-state :light-shields target])
                 metadata (:metadata request)
                 ticks (long (or (:ticks shield) 0))
                 last-absorb (long (or (:last-absorb-tick shield) -1))
                 interval (long (or (skill-config/tunable-int
                                     :light-shield :combat.absorb-interval-ticks)
                                    18))
                 base (double (:base request))
                 ;; Rear/unknown direction fails closed. The AC boundary must
                 ;; supply this neutral fact from the authoritative entity
                 ;; geometry before an absorb can occur.
                 front? (and (contains? metadata :attacker-front?)
                             (boolean (:attacker-front? metadata)))
                 exp (double (ability-model/get-skill-exp
                              (get-in (or (:target-state context)
                                           (:state context) {}) [:ability-data])
                              :light-shield))
                 overload-cost (skill-config/lerp-double
                                :light-shield :cost.absorb.cp exp)
                 cp-cost (skill-config/lerp-double
                          :light-shield :cost.absorb.overload exp)
                 cap (skill-config/lerp-double
                      :light-shield :combat.absorb-damage exp)
                 resources (or (:resources (:target-state context))
                               (:resources (:state context)) {})
                 enough? (and (>= (double (or (:overload resources) 0.0))
                                 cp-cost)
                             (>= (double (or (:cp resources) 0.0))
                                 overload-cost))
                 eligible? (and shield
                                (light-shield-state/eligible-absorb?
                                 {:ticks ticks
                                  :last-absorb-tick last-absorb
                                  :interval interval
                                  :front? front?
                                  :damage base})
                                enough?
                                (Double/isFinite cap)
                                (<= 0.0 cap 100.0))]
             (if eligible?
               (let [absorbed (min base cap)
                     next-base (- base absorbed)]
                 (-> request
                     (assoc :base next-base)
                     (assoc-in [:metadata :resource-cost]
                               {:overload (- cp-cost)
                                :cp (- overload-cost)})
                     (update :state-patch (fnil conj [])
                             [:ability-exp :light-shield 0.001])
                     (update :events (fnil conj [])
                             {:type :light-shield-absorb
                              :owner target
                              :tick (long (:tick context))
                              :event-id [:light-shield-absorb target
                                         (long (:tick context))]})))
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
              ;; Conservative: block-charging only (current-charging's own
              ;; ActMoveTo item-charging branch needs a resolved Player
              ;; object for hand-item access, which query-port fns can't
              ;; get -- same constraint documented on :shift-teleport/
              ;; :mag-manip). Raycast reuses the plain block trace, not the
              ;; deleted version's entity-priority nearestViewHit (an
              ;; entity blocking the beam without taking the charge itself).
              :charge-target (fn [context node]
                               (if-let [host-query (contract/host-port :query)]
                                 (host-query :charge-target context node)
                                 (let [owner (:owner context)
                                       world-id (geom/world-id-of owner)
                                       eye (geom/eye-pos owner)
                                       look (when (raycast/available?)
                                              (raycast/player-look-vector owner))
                                       distance (double (or (:distance node) 15.0))]
                                   (when (and world-id look)
                                     (when-let [hit (raycast/raycast-blocks
                                                     world-id (:x eye) (:y eye) (:z eye)
                                                     (double (or (:x look) 0.0))
                                                     (double (or (:y look) 0.0))
                                                     (double (or (:z look) 1.0))
                                                     distance)]
                                       {:world-id world-id
                                        :x (long (:x hit)) :y (long (:y hit)) :z (long (:z hit))})))))
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
              :storm-wing (fn [context node]
                            (if-let [host-query (contract/host-port :query)]
                              (host-query :storm-wing context node)
                              ;; execute-storm-wing! only requires query-result
                              ;; to be a non-nil map (see combat_runtime.clj's
                              ;; :storm-wing world-effect valid? check, which
                              ;; never reads any field out of it) and reads
                              ;; on-ground status itself, directly, since
                              ;; that's a live entity property the platform
                              ;; layer already has a primitive for -- no query
                              ;; round-trip needed.
                              {}))
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
              :scatter-bomb (fn [context node]
                              (if-let [host-query (contract/host-port :query)]
                                (host-query :scatter-bomb context node)
                                ;; Same as electron-missile -- execute-scatter-
                                ;; bomb! does its own targeting.
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
                      :mark
                      (let [eye (geom/eye-pos owner)
                            look (when (raycast/available?) (raycast/player-look-vector owner))
                            dist (mark-teleport-dest/max-distance exp cp hold-ticks)
                            hit (when (and look (raycast/available?))
                                  (raycast/raycast-from-player owner dist true))
                            dest (when look
                                   (mark-teleport-dest/destination
                                    {:hit hit :head-blocked? head-blocked?
                                     :x (:x eye) :eye-y (:y eye) :z (:z eye)
                                     :look-vec look :dist dist}))]
                        (when (and dest
                                   (>= (mark-teleport-dest/distance-from
                                        (:x eye) (:y eye) (:z eye) dest)
                                       (mark-teleport-dest/min-distance)))
                          {:x (:target-x dest) :y (:target-y dest) :z (:target-z dest)}))

                      :penetrate
                      (let [eye (geom/eye-pos owner)
                            look (when (raycast/available?) (raycast/player-look-vector owner))
                            dist (penetrate-dest/clamp-distance-by-cp
                                  (penetrate-dest/max-distance exp) cp exp)
                            collidable? (fn [x y z] (block-manipulation/block-collidable? world-id x y z))
                            result (when look
                                     (penetrate-dest/destination
                                      {:x (:x eye) :y (:y eye) :z (:z eye)
                                       :look-vec look :distance dist :collidable? collidable?}))]
                        ;; :available? false means the march ended still
                        ;; inside the wall it was penetrating -- teleporting
                        ;; there would bury the player in a block, so this
                        ;; must read as "no destination", not "destination
                        ;; found, deal with it later".
                        (when (and result (:available? result))
                          {:x (:x result) :y (:y result) :z (:z result)}))

                      :flashing
                      (let [body (geom/body-pos owner)
                            eye (geom/eye-pos owner)
                            look (when (raycast/available?) (raycast/player-look-vector owner))
                            dist (flashing-dest/blink-distance exp)
                            raycast-fn (fn [sx sy sz dx dy dz max-dist]
                                         (when (raycast/available?)
                                           (raycast/raycast-combined world-id sx sy sz dx dy dz max-dist)))
                            dest (when look
                                   (flashing-dest/destination
                                    {:x (:x body) :y (:y body) :z (:z body) :eye-y (:y eye)
                                     :look-vec look :direction :forward :dist dist
                                     :raycast raycast-fn :head-blocked? head-blocked?}))]
                        (when dest
                          {:x (:to-x dest) :y (:to-y dest) :z (:to-z dest)}))

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
              :jet-engine (fn [context node]
                            (when-let [host-query (contract/host-port :query)]
                              (host-query :jet-engine context node)))
              :light-shield (fn [context node]
                              (if-let [host-query (contract/host-port :query)]
                                (host-query :light-shield context node)
                                ;; execute-light-shield! finds nearby entities
                                ;; itself; :require never gates on this step's
                                ;; result.
                                {}))
              ;; Conservative reimplementation: the pre-combat-core MagManip
              ;; defskill (deleted in a8c000766) spawned a real physics block
              ;; entity that homed toward the crosshair while held and dealt
              ;; damage/placed itself via its own collision. That entity type
              ;; (ScriptedBlockBodyEntity) still exists per MC version, but
              ;; wiring it up needs a new uuid->Player platform op (spawning
              ;; and hand-item ops only accept an already-resolved Player,
              ;; which query-port fns never have -- only a uuid). Deferred;
              ;; see docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md B section.
              ;; This version keeps only what :require already validates:
              ;; grab a metal block in range, and on release deal direct
              ;; damage to whatever entity is under the crosshair -- no
              ;; hold-visual, no homing, no thrown-block flight/placement.
              :mag-manip
              (fn [context node]
                (if-let [host-query (contract/host-port :query)]
                  (host-query :mag-manip context node)
                  (let [owner (:owner context)
                        owner-key (str owner)]
                    (case (:phase context)
                      :start
                      (when-let [hit (raycast-metal-block
                                      owner (double (or (:grab-range node) 10.0)))]
                        (when (block-manipulation/can-break-block?
                               owner (:world-id hit) (:x hit) (:y hit) (:z hit))
                          (block-manipulation/break-block!
                           owner (:world-id hit) (:x hit) (:y hit) (:z hit) false)
                          (swap! mag-manip-held* assoc owner-key
                                 {:world-id (:world-id hit) :block-id (:block-id hit)})
                          hit))

                      :release
                      (when-let [held (get @mag-manip-held* owner-key)]
                        (swap! mag-manip-held* dissoc owner-key)
                        (let [world-id (:world-id held)
                              eye (geom/eye-pos owner)
                              look (when (raycast/available?)
                                     (raycast/player-look-vector owner))
                              range (double (or (:throw-range node) 20.0))
                              hit (when look
                                    (raycast/raycast-combined
                                     world-id (:x eye) (:y eye) (:z eye)
                                     (double (or (:x look) 0.0))
                                     (double (or (:y look) 0.0))
                                     (double (or (:z look) 1.0))
                                     range))
                              target-uuid (when (= :entity (:hit-type hit))
                                            (:uuid hit))]
                          {:block-id (:block-id held) :target-uuid target-uuid}))

                      nil))))
              ;; execute-mag-movement! already reads :target-x/:target-y/
              ;; :target-z off the query result and pulls the player toward
              ;; it every pulse -- only targeting was missing.
              :mag-movement
              (fn [context node]
                (if-let [host-query (contract/host-port :query)]
                  (host-query :mag-movement context node)
                  (let [owner (:owner context)
                        range (double (or (:range node) 25.0))]
                    (when-let [hit (raycast-metal-block owner range)]
                      {:target-x (+ 0.5 (double (:x hit)))
                       :target-y (+ 0.5 (double (:y hit)))
                       :target-z (+ 0.5 (double (:z hit)))}))))
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
                         ;; Block-charging only -- see :charge-target's
                         ;; comment above. Doesn't route through
                         ;; world-effects/execute-*! (platform-src) like
                         ;; every other case here: ac.energy.operations is
                         ;; AC-layer, and platform code must never require
                         ;; AC namespaces, so this mutation has to happen
                         ;; right here instead. No multiblock controller
                         ;; resolution (deleted version's
                         ;; resolve-energy-target-tile) -- charges whatever
                         ;; tile is at the hit position directly, so aiming
                         ;; at a non-controller cell of a multiblock machine
                         ;; won't charge it. No overload-floor enforcement
                         ;; or effective/ineffective exp tracking either.
                         :charge-energy
                         (let [{:keys [world-id query-result amount]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id (map? query-result)
                                            (every? #(number? (get query-result %)) [:x :y :z])
                                            (finite? amount) (pos? (double amount))
                                            (<= (double amount) 1000.0))
                               tile (when valid?
                                      (block-entity-at world-id
                                                        (:x query-result)
                                                        (:y query-result)
                                                        (:z query-result)))
                               charged? (when tile
                                          (try
                                            (cond
                                              (energy/is-node-supported? tile)
                                              (< (double (energy/charge-node tile (double amount) false))
                                                 (double amount))

                                              (energy/is-receiver-supported? tile)
                                              (< (double (energy/charge-receiver tile (double amount)))
                                                 (double amount))

                                              :else false)
                                            (catch Exception _ false)))]
                           {:status (if charged? :applied :failed)
                            :effect effect})
                         ;; Unconditional self-effect + a client-facing FX
                         ;; event -- the deleted version never actually
                         ;; scanned for ore blocks server-side despite the
                         ;; skill's name; the block detection/highlight is
                         ;; presumably client-only rendering driven by the
                         ;; :range/:advanced? params in the vfx step, outside
                         ;; combat-core's authority. See combat_content.clj's
                         ;; :mine-detect entry -- it used to gate on a
                         ;; :block-scan query result, which the real
                         ;; mechanic never did.
                         :mine-detect
                         (let [{:keys [blindness-ticks blindness-amplifier]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and (finite? blindness-ticks)
                                            (<= 0.0 (double blindness-ticks) 1000.0)
                                            (finite? blindness-amplifier)
                                            (<= 0.0 (double blindness-amplifier) 10.0)
                                            (potion-effects/available?))]
                           {:status (if (and valid?
                                              (potion-effects/apply-effect!
                                               owner :blindness
                                               (long blindness-ticks)
                                               (long blindness-amplifier)))
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
                         ;; Conservative: query-result is {:block-id
                         ;; :target-uuid} from the release-phase query above,
                         ;; not the entity-uuid/position/throw-target fields
                         ;; the old physics-body executor read. See that
                         ;; query's comment and COMBAT_VFX_PLATFORM_GAPS.md B
                         ;; section for why the full mechanic is deferred.
                         :mag-manip
                         (let [{:keys [world-id query-result mode throw-range damage]} effect
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               plan {:query-result query-result
                                     :session-id (:session-id effect)
                                     :mode mode
                                     :throw-range throw-range
                                     :damage (double (or damage 0.0))}
                               valid? (and world-id (map? query-result)
                                            (= :throw mode)
                                            (finite? throw-range)
                                            (<= 1.0 (double throw-range) 64.0)
                                            (finite? damage)
                                            (<= 0.0 (:damage plan) 1000.0)
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
                             ;; :mark / :penetrate / :flashing -- a genuine
                             ;; player teleport. The destination is always
                             ;; server-computed (raycast/eye-position/
                             ;; collision checks in the query, never trusts
                             ;; client input), so the token here isn't a
                             ;; security boundary -- it's just filling the
                             ;; existing teleport-approved-target! contract
                             ;; shape (owner ability-id approval-token mode)
                             ;; uniformly across every mode.
                             (let [valid-dest? (and (map? destination)
                                                     (every? #(number? (get destination %)) [:x :y :z]))
                                   valid? (and world-id valid-dest?
                                                (#{:mark-teleport :penetrate-teleport :flashing} ability-id)
                                                (#{:mark :penetrate :flashing} mode)
                                                (teleportation/available?))
                                   token (when valid?
                                           (teleportation/mint-approval-token!
                                            {:world-id world-id
                                             :x (:x destination) :y (:y destination) :z (:z destination)}))
                                   applied? (when token
                                              (teleportation/teleport-approved-target!
                                               owner ability-id token mode))]
                               {:status (if applied? :applied :failed)
                                :effect effect})))
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
     :active-abilities (if-let [session (edn-sessions/session (str owner))]
                         #{(:ability-id session)}
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
  where neutral paths become persistent state transitions."
  [patch-actions]
  (keep (fn [{:keys [entries]}]
          (some (fn [{:keys [path mode value]}]
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

(defn- parameter-snapshot
  "Read immutable parameter values materialized during catalog loading.

  Combat-core never reads live AC configuration and the EDN document never
  contains config paths. The catalog loader owns the one-way config overlay;
  activation only snapshots those already-resolved values."
  [ability-id ability intent]
  (if (contains? intent :parameter-snapshot)
    (:parameter-snapshot intent)
    (into {}
          (map (fn [[parameter-id declaration]]
                 (when-not (contains? declaration :value)
                   (throw (ex-info "EDN parameter was not materialized"
                                   {:ability-id ability-id
                                    :parameter parameter-id})))
                 [parameter-id (:value declaration)])
               (:parameters ability)))))

(defn- activation-context
  [owner ability-id intent]
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
            :activation-seed (long (or (:activation-seed intent)
                                       (hash [owner ability-id])))
            :skill-exp (double (or (get-in state [:ability-data :skill-exps ability-id])
                                   0.0))
            :resources {:cp (double (or (:cur-cp resource-data) 0.0))
                        :overload (double (or (:cur-overload resource-data) 0.0))}
            :creative? false}
           (:context intent))))

(defn- install-edn-host-capabilities!
  "Link the generic EDN host table to neutral AC platform ports once.

  The handlers exchange only maps and UUIDs.  They do not select abilities or
  contain Arc Gen rules; those remain in EDN."
  []
  (when (compare-and-set! edn-host-capabilities-installed? false true)
    (try
      (when-not (contains? (:queries (capabilities/snapshot)) :raycast)
        (capabilities/register-query!
         :raycast
         (fn [{:keys [owner distance include-entities? include-blocks?]} _frame]
           (let [owner (str owner)
                 distance (max 0.0 (min 128.0 (double (or distance 0.0))))
                 world-id (geom/world-id-of owner)
                 eye (geom/eye-pos owner)
                 look (when (raycast/available?)
                        (raycast/player-look-vector owner))
                 hit (when (and look (raycast/available?))
                       (let [sx (:x eye) sy (:y eye) sz (:z eye)
                             dx (double (or (:x look) 0.0))
                             dy (double (or (:y look) 0.0))
                             dz (double (or (:z look) 1.0))]
                         (cond
                           (and (not= false include-entities?)
                                (not= false include-blocks?))
                           (raycast/raycast-combined world-id sx sy sz dx dy dz distance)
                           (not= false include-entities?)
                           (raycast/raycast-entities world-id sx sy sz dx dy dz distance)
                           (not= false include-blocks?)
                           (raycast/raycast-blocks world-id sx sy sz dx dy dz distance)
                           :else nil)))
                 kind (cond
                        (= :entity (:hit-type hit)) :entity
                        (= :block (:hit-type hit)) :block
                        (:entity-id hit) :entity
                        (:uuid hit) :entity
                        hit :block
                        :else :miss)
                 position (case kind
                            :entity {:x (double (or (:hit-x hit) (:x hit) 0.0))
                                     :y (double (or (:hit-y hit) (:y hit) 0.0))
                                     :z (double (or (:hit-z hit) (:z hit) 0.0))}
                            :block {:x (double (or (:hit-x hit) (:x hit) 0.0))
                                    :y (double (or (:hit-y hit) (:y hit) 0.0))
                                    :z (double (or (:hit-z hit) (:z hit) 0.0))}
                            {:x (+ (:x eye) (* (double (or (:x look) 0.0)) distance))
                             :y (+ (:y eye) (* (double (or (:y look) 0.0)) distance))
                             :z (+ (:z eye) (* (double (or (:z look) 1.0)) distance))})
                 block-id (:block-id hit)]
             {:hit-type kind
              :entity-id (or (:entity-id hit) (:uuid hit))
               :creeper? (contains? #{"minecraft:creeper" "entity.minecraft.creeper"}
                                     (or (:entity-type hit) (:type hit)))
              :position position
              :block-position {:x (Math/floor (double (:x position)))
                               :y (Math/floor (double (:y position)))
                               :z (Math/floor (double (:z position)))}
              :block-id block-id
              :water? (= "minecraft:water" block-id)
              :world-id world-id}))))
      (when-not (contains? (:queries (capabilities/snapshot)) :entity/select)
        (capabilities/register-query!
         :entity/select
         (fn [{:keys [owner world-id shape filter projection limit]} _frame]
           (let [center (or (:center shape) {})
                 [cx cy cz] (if (and (map? center) (vector? (:vec3 center)))
                              (:vec3 center)
                              [(double (or (:x center) 0.0))
                               (double (or (:y center) 0.0))
                               (double (or (:z center) 0.0))])
                 radius (double (or (:radius shape) 0.0))
                 limit (max 0 (min 256 (long (or limit 0))))
                 owner-id (str owner)
                 type-filter (set (or (:entity-types filter) []))
                 excluded-filter (set (or (:excluded-entity-ids filter) []))
                 difficulty-map (reduce
                                  (fn [result entry]
                                    (if (string? entry)
                                      (let [index (.lastIndexOf ^String entry ":")]
                                        (if (pos? index)
                                          (try
                                            (assoc result
                                                   (subs entry 0 index)
                                                   (Double/parseDouble
                                                    (subs entry (inc index))))
                                            (catch Throwable _ result))
                                          result))
                                      result))
                                  {}
                                  (or (:difficulty-entries filter) []))]
             (when (and (= :sphere (:type shape)) world-id
                        (every? #(Double/isFinite (double %)) [cx cy cz])
                        (Double/isFinite radius) (<= 0.0 radius 64.0)
                        (pos? limit) (world-effects/available?))
               (let [project (fn [entity]
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
                                                    :explosion-power (:explosion-power entity)
                                                    nil)))
                                         {} (or projection [:id :type :position]))))]
                 (->> (world-effects/find-entities-in-radius world-id cx cy cz radius)
                      (filter map?)
                      (remove #(= owner-id (str (or (:uuid %) (:entity-id %)))))
                      (filter #(let [entity-type (or (:entity-type %) (:type %))]
                                 (and (or (empty? type-filter)
                                          (contains? type-filter entity-type))
                                      (not (contains? excluded-filter entity-type))
                                      (not (:item? %))
                                      (not (:living? %))
                                      (not (:mob? %))
                                      (not (:multipart? %)))))
                      (map project)
                      (take limit)
                      vec)))))))
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
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/damage)
        (capabilities/register-action!
         :entity/damage
         (fn [{:keys [world-id target amount damage-type owner]}]
           (when (and target (entity-damage/available?)
                      (Double/isFinite (double amount))
                      (pos? (double amount)))
             (entity-damage/apply-direct-damage!
              world-id target (double amount) (or damage-type :generic)
              {:attacker-uuid owner})))))
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
      (catch Throwable _
        ;; A loader may freeze the registry before AC content boots.  Leave the
        ;; registry state authoritative; missing ports surface as :unhandled.
        (reset! edn-host-capabilities-installed? false)))
  (capabilities/snapshot)))

(defn- execute-edn-intent!
  "Execute a normalized EDN intent against the AC-owned session index." 
  [owner intent]
  (let [ability-id (edn-ability-id owner intent)
        requested-op (or (:action intent) (:op intent) :start)
        current-tick (long (or (:server-tick intent) @last-known-tick* 0))
        ability (get-in (edn-catalog/catalog) [:combat :abilities ability-id])
        active-session (edn-sessions/session owner)
        toggle-close? (and (= :start requested-op)
                           active-session
                           (= :toggle (:activation ability))
                           (= ability-id (:ability-id active-session)))
        op (if toggle-close? :abort requested-op)
        normalized (assoc intent
                          :action op
                          :ability-id ability-id
                          :context (or (:context active-session)
                                       (activation-context owner ability-id intent))
                          :parameter-snapshot
                          (or (:parameter-snapshot intent)
                              (:parameter-snapshot active-session)
                              (parameter-snapshot ability-id ability intent)))]
    (when (= :start op)
      (edn-sessions/start! owner ability-id normalized))
    (let [session (edn-sessions/session owner)
          session-context (edn-sessions/context-for owner normalized)
          start-tick (long (or (:start-tick session) current-tick))
          dynamic-context (merge (:context session-context)
                                 {:server-tick current-tick
                                  :session-start-tick start-tick
                                  :hold-ticks (max 0 (- current-tick start-tick))})
          execution-intent (merge normalized
                                  (select-keys session-context
                                               [:context :parameter-snapshot
                                                :activation-seed])
                                  {:context dynamic-context
                                   :session-state (:state session-context)
                                   :latches (:latches session-context)
                                   :server-tick current-tick})
          result (edn-execution/execute! ability-id owner execution-intent)]
      (when (= :accepted (:status result))
        (edn-sessions/apply-actions! owner (:actions result)))
      (when (or (:finish-session? result)
                (and (#{:release :abort} op)
                 (or (= :accepted (:status result))
                     (= :rejected (:status result)))))
        (edn-sessions/remove! owner))
      result)))

(defn dispatch-intent! [owner intent]
  ;; The migration table is authoritative at the server boundary.  Pending
  ;; skills never reach the legacy engine and therefore have no compatibility
  ;; fallback; once their EDN catalog entry is migrated this gate naturally
  ;; opens without changing the core runtime.
  (let [;; Slot resolution is server-owned preset data, never a client
        ;; ability/event mapping.  The resolved id is then checked against the
        ;; migrated EDN catalog before execution.
        ability-id (edn-ability-id owner intent)]
    (if-not (edn-catalog/available? ability-id)
      {:schema-version 2
        :status :rejected
        :reason :ability-not-migrated
        :feedback [{:type :ability-not-migrated
                    :ability-id ability-id
                    :status (edn-catalog/migration-status ability-id)}]}
      (do
        (install-edn-host-capabilities!)
        (execute-edn-intent! owner intent)))))

(defn dispatch-trigger!
  "Dispatch a server-resolved external trigger from the EDN trigger index.

  The trigger map is produced by `edn-catalog/resolve-trigger`; clients never
  provide ability/event mappings." 
  [owner trigger context]
  (when (and (map? trigger) (:ability trigger) (:event trigger))
    ;; An external event is a terminal interruption for the owner session.
    ;; Run the generic EDN abort phase first so session-scoped VFX and other
    ;; cleanup actions are finalized through the same commit boundary.
    (when-let [session (edn-sessions/session owner)]
      (let [abort-result (execute-edn-intent!
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

(defn- scale-damage-components
  "Scale numeric damage components while retaining their original shape."
  [value factor]
  (cond
    (number? value) (* (double value) (double factor))
    (map? value) (reduce-kv (fn [m k v]
                              (assoc m k (scale-damage-components v factor)))
                            (empty value) value)
    (vector? value) (mapv #(scale-damage-components % factor) value)
    (seq? value) (mapv #(scale-damage-components % factor) value)
    :else value))

(defn- reaction-value
  "Resolve the bounded expression subset used by EDN damage reactions."
  [value context]
  (cond
    (and (map? value) (:expr value))
    (combat-vm/evaluate-expression
     (:expr value)
     (mapv #(reaction-value % context) (:args value))
     (long (or (:activation-seed context) 0)))

    (and (map? value) (:ref value))
    (let [[scope key & path] (:ref value)
          root (case scope
                 :context (:context context)
                 :request (:request context)
                 :param (:params context)
                 :state (:state context)
                 {})]
      (get-in root (into [key] path)))

    (map? value) (reduce-kv (fn [m k v]
                              (assoc m k (reaction-value v context)))
                            (empty value) value)
    (vector? value) (mapv #(reaction-value % context) value)
    :else value))

(defn- apply-edn-damage-reactions
  "Run all EDN reactions for a damage request in deterministic priority order.

  A reaction never reconstructs a damage type. Its reflected request is a
  copy of the incoming request with only source/target, amount, and recursion
  metadata changed. This keeps armor/resistance/component semantics intact."
  [request {:keys [precheck?] :as boundary}]
  (let [abilities (vals (get-in (edn-catalog/catalog) [:combat :abilities]))
        reactions (->> abilities
                       (mapcat (fn [ability]
                                 (for [reaction (:reactions ability)
                                       :when (= :combat/damage (:on reaction))]
                                   (assoc reaction :ability-id (:id ability)))))
                       (sort-by (juxt #(long (or (:priority %) 0))
                                      #(str (:ability-id %)))))]
    (reduce
      (fn [current {:keys [ability-id when program]}]
        (let [target (str (:target current))
              session (edn-sessions/session target)
              ability (get-in (edn-catalog/catalog) [:combat :abilities ability-id])
              params (:parameter-snapshot session)
              state (owner-state target)
              context {:request current
                       :state state
                       :params params
                       :activation-seed (long (or (:activation-seed session) 0))
                       :context {:enabled? (boolean session)
                                 :resource (double (or (get-in state [:resources :cp]) 0.0))
                                 :skill-exp (double (or (get-in state
                                                                [:ability-data :skill-exps ability-id])
                                                         0.0))
                                 :depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                                 :damage (double (:base current))}}
              condition? (or (nil? when)
                             (boolean (reaction-value when context)))
              component (:component program)]
          (if-not (and session ability condition? (= :damage/reflect component))
            current
            (let [multiplier (double (or (reaction-value (:multiplier program) context) 0.0))
                  cost-rate (double (or (reaction-value (:cost-per-damage program) context) 0.0))
                  minimum (double (or (reaction-value (:minimum program) context) 0.0))
                  max-depth (long (or (reaction-value (:max-depth program) context) 0))
                  exp-scale (double (or (reaction-value (:exp-scale program) context) 0.0))
                  depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                  base (double (:base current))
                  reflected (* base multiplier (Math/pow 0.5 (double depth)))
                  cp (double (or (get-in state [:resources :cp]) 0.0))
                  consumption (max 0.0 (* base cost-rate))
                  source (:source current)
                  eligible? (and source (not= (str source) target)
                                 (Double/isFinite base) (pos? base)
                                 (Double/isFinite reflected)
                                 (>= reflected minimum)
                                 (< depth max-depth)
                                 (pos? consumption)
                                 (>= cp consumption))]
              (if-not eligible?
                current
                (let [ratio (if (pos? base) (/ reflected base) 0.0)
                      reflected-request
                      (-> current
                          (assoc :source target :target source :base reflected)
                          (cond-> (vector? (:direction current))
                            (assoc :direction
                                   (mapv #(- (double %)) (:direction current))))
                          (assoc :components
                                 (scale-damage-components (:components current) ratio))
                          (update :tags (fnil conj #{}) :reflected)
                          (update :metadata merge
                                  {:reflection-source? true
                                   :reflection-depth (inc depth)
                                   :reflection-ability ability-id}))]
                  (-> current
                      (assoc :base (max 0.0 (- base reflected)))
                      (assoc-in [:metadata :resource-cost] {:cp (- consumption)})
                      (update :state-patch (fnil conj [])
                              [:ability-exp ability-id (* base exp-scale)])
                      (update :world-effects (fnil conj [])
                              {:type :damage :request reflected-request})
                      (cond-> precheck? (assoc :cancelled? true)))))))))
      request reactions)))

(defn process-damage-request!
  "Authoritative damage interception boundary for platform adapters.

   The old mutable damage-handler registry is not consulted. Combat Core
   returns the transformed neutral request; the platform writes only the
   resulting numeric amount back to its event."
  [player-id attacker-id original-damage damage-source]
  (let [request (apply-edn-damage-reactions
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
  (let [request (apply-edn-damage-reactions
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
        damage? (damage-output? request)]
    (when damage?
      (commit-state-patch! player-id (:state-patch request))
      (execute-damage-effects! player-id request)
      (dispatch-result-domain-events! player-id request))
    (boolean damage?)))

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
                       (edn-execution/commit-actions!
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
                (execute-edn-intent!
                 owner
                 {:op :pulse
                  :action :pulse
                  :ability-id (:ability-id session)
                  :server-tick (long tick)}))
              (edn-sessions/tick! tick))
        ]
    (mapv (fn [result]
            ;; Session pulses produce authoritative patches before effects
            ;; and publication, exactly like start/release intents.
            (when (= :accepted (:status result))
              (commit-state-patch! (:owner result) (:state-patch result)))
            (publish-result! (finalize-result! (:owner result) result)))
          edn-results)))
(defn abort-owner! [owner]
  (edn-sessions/remove! owner)
  nil)
(defn snapshot-owner [owner]
  {:edn-session (edn-sessions/session owner)})

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! world-effect-handler* nil)
  (reset! result-sink* nil)
  (reset! last-known-tick* 0)
  (edn-sessions/reset-for-test!)
  nil)
