(ns cn.li.ac.content.ability.teleporter.shift-teleport

  "ShiftTeleport skill - teleport to target location while transporting a held block.



  Pattern: :release-cast

  Mechanic: Resolve look target, place held block on the hit face when possible,

            fallback to dropping one held item at target point, then teleport and

            apply line-hit magic damage.

  Range: lerp(25, 35, exp)

  CP cost (up): lerp(260, 320, exp)

  Overload (up): lerp(40, 30, exp)

  Cooldown: lerp(100, 60, exp) ticks

  Exp: (1 + hit-count) * exp-base



  No Minecraft imports."

  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]

            [cn.li.ac.ability.fx :as fx]

            [cn.li.ac.ability.service.context-dispatcher :as ctx]

            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]

                        [cn.li.ac.ability.service.skill-effects :as skill-effects]

            [cn.li.ac.ability.effects.geom :as geom]

            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.content.ability.teleporter.release-cast-base :as release-cast]

                        [cn.li.mcmod.platform.entity :as entity]

            [cn.li.mcmod.platform.raycast :as raycast]

            [cn.li.mcmod.platform.world-effects :as world-effects]

            [cn.li.mcmod.util.log :as log]))



;; ---------------------------------------------------------------------------

;; Helpers

;; ---------------------------------------------------------------------------



(def-skill-config-ops :shift-teleport)

(def ^:private shift-teleport-skill-id :shift-teleport)



(def ^:private face-offsets

  {:up [0 1 0]

   :down [0 -1 0]

   :north [0 0 -1]

   :south [0 0 1]

   :west [-1 0 0]

   :east [1 0 0]})



(defn- floor-int [x]

  (int (Math/floor (double x))))



(defn- hand-placeable-block?

  [player]

  (boolean (and player (entity/player-main-hand-placeable-block? player))))



(defn- resolve-target

  [player-id max-range]

  (when (raycast/available?)

    (let [player-pos (helper/player-position player-id)

          look-vec (helper/player-look-vec player-id)]

      (when (and player-pos look-vec)

        (let [world-id (geom/world-id-of player-id)

              eye-x (double (:x player-pos))

              eye-y (+ (double (:y player-pos))

                       (cfg-double :targeting.eye-height))

              eye-z (double (:z player-pos))

              hit (raycast/raycast-blocks*

                    world-id

                    eye-x eye-y eye-z

                    (double (:x look-vec))

                    (double (:y look-vec))

                    (double (:z look-vec))

                    (double max-range))

              face (or (:face hit) :down)

              [ox oy oz] (get face-offsets face [0 -1 0])

              hit-x (if hit (double (:x hit)) (+ eye-x (* (double (:x look-vec)) (double max-range))))

              hit-y (if hit (double (:y hit)) (+ eye-y (* (double (:y look-vec)) (double max-range))))

              hit-z (if hit (double (:z hit)) (+ eye-z (* (double (:z look-vec)) (double max-range))))

              bx (if hit (int (:x hit)) (floor-int hit-x))

              by (if hit (int (:y hit)) (floor-int hit-y))

              bz (if hit (int (:z hit)) (floor-int hit-z))

              place-x (+ bx ox)

              place-y (+ by oy)

              place-z (+ bz oz)]

          {:world-id world-id

           :eye-pos {:x eye-x :y eye-y :z eye-z}

           :drop-x (+ (double place-x) 0.5)

           :drop-y (double place-y)

           :drop-z (+ (double place-z) 0.5)

           :dest-x (+ (double place-x) 0.5)

           :dest-y (double place-y)

           :dest-z (+ (double place-z) 0.5)

           :place-x place-x

           :place-y place-y

           :place-z place-z

           :face face

           :target-hit? (boolean hit)})))))



(defn- segment-intersects-aabb?

  "Return true when segment p0->p1 intersects axis-aligned box {min,max}."

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

                        (when (<= ntmin ntmax)

                          [ntmin ntmax]))))]

    (when-let [[tmin tmax] (axis-step (double x) dx (double min-x) (double max-x) 0.0 1.0)]

      (when-let [[tmin tmax] (axis-step (double y) dy (double min-y) (double max-y) tmin tmax)]

        (when-let [[_ _] (axis-step (double z) dz (double min-z) (double max-z) tmin tmax)]

          true)))))



(defn- entity-aabb

  [entity]

  (let [x (double (:x entity))

        y (double (:y entity))

        z (double (:z entity))

        half-w (/ (double (or (:width entity) 0.6)) 2.0)

        h (double (or (:height entity) 1.8))]

    {:min-x (- x half-w)

     :max-x (+ x half-w)

     :min-y y

     :max-y (+ y h)

     :min-z (- z half-w)

     :max-z (+ z half-w)}))



(defn- point-line-distance-sq

  [{sx :x sy :y sz :z} {ex :x ey :y ez :z} {px :x py :y pz :z}]

  (let [vx (- (double ex) (double sx))

        vy (- (double ey) (double sy))

        vz (- (double ez) (double sz))

        wx (- (double px) (double sx))

        wy (- (double py) (double sy))

        wz (- (double pz) (double sz))

        len-sq (+ (* vx vx) (* vy vy) (* vz vz))

        t (if (pos? len-sq)

            (max 0.0 (min 1.0 (/ (+ (* wx vx) (* wy vy) (* wz vz)) len-sq)))

            0.0)

        qx (+ (double sx) (* vx t))

        qy (+ (double sy) (* vy t))

        qz (+ (double sz) (* vz t))

        dx (- (double px) qx)

        dy (- (double py) qy)

        dz (- (double pz) qz)]

    (+ (* dx dx) (* dy dy) (* dz dz))))



(defn- line-target-filter?

  "True when entity intersects the line segment and is not the player."

  [player-id eye-pos dest-pos entity]

  (let [uuid (str (:uuid entity))]

    (and (seq uuid)

         (not= uuid (str player-id))

         (segment-intersects-aabb? eye-pos dest-pos (entity-aabb entity)))))



(defn- line-target-dist-sq

  "Squared distance from entity to the line segment, for nearest-first sort."

  [eye-pos dest-pos entity]

  (point-line-distance-sq eye-pos dest-pos

                          {:x (:x entity) :y (:y entity) :z (:z entity)}))



(defn- line-targets

  "Return entities intersecting segment eye->destination in stable near-to-far order."

  [player-id world-id eye-pos dest-pos]

  (if-not (and (world-effects/available?) eye-pos dest-pos)

    []

    (let [min-x (min (double (:x eye-pos)) (double (:x dest-pos)))

          min-y (min (double (:y eye-pos)) (double (:y dest-pos)))

          min-z (min (double (:z eye-pos)) (double (:z dest-pos)))

          max-x (max (double (:x eye-pos)) (double (:x dest-pos)))

          max-y (max (double (:y eye-pos)) (double (:y dest-pos)))

          max-z (max (double (:z eye-pos)) (double (:z dest-pos)))

          candidates (world-effects/find-entities-in-aabb*

                       world-id

                       min-x min-y min-z

                       max-x max-y max-z)]

      (->> candidates

           (filter (partial line-target-filter? player-id eye-pos dest-pos))

           (sort-by (partial line-target-dist-sq eye-pos dest-pos))

           (reduce (fn [acc entity]

                     (let [uuid (str (:uuid entity))]

                       (if (contains? (:seen acc) uuid)

                         acc

                         {:seen (conj (:seen acc) uuid)

                          :entities (conj (:entities acc) entity)})))

                   {:seen #{} :entities []})

           :entities))))



(defn- build-trace

  [player-id]

  (let [exp (skill-exp player-id)

        range (cfg-lerp :targeting.range exp)

        target (resolve-target player-id range)]

    (when target

      (let [line-end {:x (:dest-x target)

                      :y (+ (:dest-y target) 0.5)

                      :z (:dest-z target)}

            entities (line-targets player-id

                                   (:world-id target)

                                   (:eye-pos target)

                                   line-end)]

        (assoc target

               :range range

               :exp exp

               :entities entities)))))



(defn- try-place-or-drop!

  [player trace]

  (let [place-result (entity/player-place-main-hand-block-at-hit!

                       player

                       (:world-id trace)

                       (:place-x trace)

                       (:place-y trace)

                       (:place-z trace)

                       (:face trace))

        placed? (boolean (:placed? place-result))

        creative? (boolean (entity/player-creative? player))

        dropped? (boolean

                   (and (not placed?)

                        (entity/player-drop-main-hand-item-at! player

                                                               1

                                                               (:drop-x trace)

                                                               (:drop-y trace)

                                                               (:drop-z trace))))

        consumed? (boolean

                    (or creative?

                        (not placed?)

                        (entity/player-consume-main-hand-item! player 1)))]

    {:placed? placed?

     :dropped? dropped?

    :consumed? consumed?

     :executed? (and consumed? (or placed? dropped?))}))





(defn- shift-tp-tick-impl!

  [ctx-id player-id _skill-id _exp _cost-ok? hold-ticks _cost-stage player-ref]

  (let [hand-valid? (hand-placeable-block? player-ref)

        trace (when hand-valid? (build-trace player-id))]

    (ctx-skill/replace-skill-state! ctx-id {:hold-ticks hold-ticks

                     :hand-valid? hand-valid?

                     :trace trace})

    (when trace

      (fx/send! ctx-id {:topic :shift-teleport/fx-update :mode :update} nil

                {:x (:dest-x trace)

                 :y (:dest-y trace)

                 :z (:dest-z trace)

                 :target-count (count (:entities trace))

                 :target-hit? (:target-hit? trace)

                 :hand-valid? hand-valid?}))))



(defn- shift-tp-up-impl!

  [ctx-id player-id _skill-id _exp cost-ok? _hold-ticks _cost-stage player-ref]

  (try

    (let [ctx-data (ctx-skill/get-context ctx-id)

          hand-valid? (hand-placeable-block? player-ref)

          trace (or (get-in ctx-data [:skill-state :trace])

                    (when hand-valid? (build-trace player-id)))]

      (when (and cost-ok? hand-valid? trace)

        (let [place-drop-result (try-place-or-drop! player-ref trace)

              damage (cfg-lerp :combat.damage (:exp trace))]

          (when (and (:executed? place-drop-result)

                     (helper/teleport-to! player-id

                                          (:world-id trace)

                                          (:dest-x trace)

                                          (:dest-y trace)

                                          (:dest-z trace)))

            (let [hit-count

                  (reduce (fn [n entity]

                            (let [target-uuid (str (:uuid entity))

                                  damage-result (helper/deal-magic-damage! player-id

                                                                           (:world-id trace)

                                                                           target-uuid

                                                                           damage)]

                              (when (helper/crit-applied? damage-result)

                                (fx/send! ctx-id {:topic :teleporter/fx-crit-hit} nil

                                          {:x (double (:x entity))

                                           :y (double (:y entity))

                                           :z (double (:z entity))

                                           :crit-level (:crit-level damage-result)

                                           :crit-rate (:crit-rate damage-result)

                                           :message-key (:message-key damage-result)

                                           :message-args (:message-args damage-result)

                                           :target-uuid target-uuid

                                           :skill-id shift-teleport-skill-id}))

                              (inc n)))

                          0

                          (:entities trace))

                  exp-base (cfg-double :progression.exp-base)]

              (skill-effects/add-skill-exp! player-id

                                            shift-teleport-skill-id

                                            (* (double exp-base) (double (inc hit-count))))

              (let [cd (cfg-lerp-int :cooldown.ticks (:exp trace))]

                (skill-effects/set-main-cooldown! player-id shift-teleport-skill-id cd))

              (fx/send! ctx-id {:topic :shift-teleport/fx-perform :mode :perform} nil

                        {:from-x (get-in trace [:eye-pos :x])

                         :from-y (get-in trace [:eye-pos :y])

                         :from-z (get-in trace [:eye-pos :z])

                         :x (:dest-x trace)

                         :y (:dest-y trace)

                         :z (:dest-z trace)

                         :target-count (count (:entities trace))

                         :placed? (:placed? place-drop-result)

                         :dropped? (:dropped? place-drop-result)})))))

      (when-not trace

        (log/debug "ShiftTeleport: failed to resolve trace target")))

    (catch Exception e

      (log/warn "ShiftTeleport up! failed:" (ex-message e)))))





(def ^:private release-cast-ops

  (release-cast/build-ops

    {:initial-state {:hold-ticks 0 :trace nil :hand-valid? true}

     :tick! shift-tp-tick-impl!

     :up! shift-tp-up-impl!}))



(defn shift-tp-down! [& args] (apply release-cast/down! release-cast-ops args))

(defn shift-tp-tick! [& args] (apply release-cast/tick! release-cast-ops args))

(defn shift-tp-up! [& args] (apply release-cast/up! release-cast-ops args))

(defn shift-tp-abort! [& args] (apply release-cast/abort! release-cast-ops args))



;; ---------------------------------------------------------------------------

;; Skill registration

;; ---------------------------------------------------------------------------



(defskill shift-teleport

  :id             :shift-teleport

  :category-id    :teleporter

  :name-key       "ability.skill.teleporter.shift_teleport"

  :description-key "ability.skill.teleporter.shift_teleport.desc"

  :icon           "textures/abilities/teleporter/skills/shift_teleport.png"

  :ui-position    [175 47]



  :ctrl-id        :shift-teleport

  :cp-consume-speed 0.0

  :overload-consume-speed 0.0

  :pattern        :release-cast

  :cost           {:up {:cp       (fn [player-id _skill-id _exp]

                                    (cfg-lerp :cost.up.cp

                                                     (skill-exp player-id)))

                        :overload (fn [player-id _skill-id _exp]

                                    (cfg-lerp :cost.up.overload

                                                     (skill-exp player-id)))}}

  :cooldown       {:mode :manual}

  :actions        {:down!  shift-tp-down!

                   :tick!  shift-tp-tick!

                   :up!    shift-tp-up!

                   :abort! shift-tp-abort!}

  :fx             {:start {:topic :shift-teleport/fx-start :payload (fn [_] {})}

                   :update {:topic :shift-teleport/fx-update :payload (fn [_] {})}

                   :end   {:topic :shift-teleport/fx-end   :payload (fn [_] {})}}

  :prerequisites  [{:skill-id :location-teleport :min-exp 0.5}])



