(ns cn.li.ac.content.ability.vecmanip.directed-blastwave
  "DirectedBlastwave - AOE blast with block breaking.

  Pattern: :charge-window (valid 6-50, abort 200)
  Cost on perform: CP lerp(160,200), overload lerp(50,30) by exp
  Cooldown: lerp(80,50) ticks by exp
  Exp: +0.0025 on hit / +0.0012 on miss"
  (:require            [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private directed-blastwave-skill-id :directed-blastwave)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double directed-blastwave-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int directed-blastwave-skill-id field-id))

(defn- cfg-double-list [field-id]
  (skill-config/tunable-double-list directed-blastwave-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double directed-blastwave-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int directed-blastwave-skill-id field-id (exp01 exp)))

(defn- player-body-pos [player-id]
  (geom/body-pos player-id))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/replace-skill-state-root! ctx-id state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn- terminate-with-end!
  [ctx-id performed?]
  (fx/send-end! ctx-id :directed-blastwave/fx-end {:performed? performed?})
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (set-skill-state-root! ctx-id (assoc (:skill-state ctx-data) :performed? performed?)))
  (clear-skill-state! ctx-id)
  (ctx/terminate-context! ctx-id nil))

(defn- hit-pos-from-trace [player-id trace]
  (let [look (or (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))
                 {:x 0.0 :y 0.0 :z 1.0})]
    (cond
      (nil? trace)
      (geom/v+ (player-body-pos player-id)
               (geom/v* (geom/vnorm look) (cfg-double :targeting.raycast-distance)))
      (= :block (:hit-type trace))
      {:x (double (int (Math/round (double (or (:x trace) 0.0)))))
       :y (double (int (Math/round (double (or (:y trace) 0.0)))))
       :z (double (int (Math/round (double (or (:z trace) 0.0)))))}
      (= :entity (:hit-type trace))
      {:x (double (or (:x trace) 0.0))
        :y (+ (double (or (:y trace) 0.0)) (double (or (:eye-height trace) (cfg-double :targeting.eye-height))))
       :z (double (or (:z trace) 0.0))}
      :else
      {:x (double (or (:x trace) 0.0))
       :y (double (or (:y trace) 0.0))
       :z (double (or (:z trace) 0.0))})))

(defn- knockback-impulse [player-id entity]
  (let [player-head (geom/eye-pos player-id)
        target-head {:x (double (or (:x entity) 0.0))
                     :y (+ (double (or (:y entity) 0.0)) (double (or (:eye-height entity) (cfg-double :targeting.eye-height))))
                     :z (double (or (:z entity) 0.0))}
        d0 (geom/vnorm (geom/v- player-head target-head))
        d1 (geom/vnorm {:x (:x d0) :y (- (:y d0) (cfg-double :movement.knockback-y-adjust)) :z (:z d0)})]
    (geom/v* d1 (cfg-double :movement.knockback-scale))))

    (defn- push-impulse [player-id entity]
      (let [player-pos (player-body-pos player-id)
        target-pos {:x (double (or (:x entity) 0.0))
              :y (double (or (:y entity) 0.0))
              :z (double (or (:z entity) 0.0))}
        d0 (geom/vnorm (geom/v- target-pos player-pos))]
      (geom/v* d0 0.24)))

(defn- break-hardness [exp]
  (let [[low-cap mid-cap high-cap] (cfg-double-list :breaking.hardness-caps)]
    (cond (< (double exp) (cfg-double :breaking.hardness-low-threshold)) low-cap
          (< (double exp) (cfg-double :breaking.hardness-mid-threshold)) mid-cap
          :else high-cap)))

(defn- break-nearby-blocks! [player-id world-id pos exp]
  (when block-manip/*block-manipulation*
    (let [x0        (int (Math/round (double (:x pos))))
          y0        (int (Math/round (double (:y pos))))
          z0        (int (Math/round (double (:z pos))))
          hard-cap  (break-hardness exp)
          p-break   (cfg-lerp :breaking.break-probability exp)
          p-drop    (cfg-lerp :breaking.drop-probability exp)
          full-exp? (= 1.0 (double (exp01 exp)))]
      (doseq [x (range (- x0 3) (+ x0 3))
              y (range (- y0 3) (+ y0 3))
              z (range (- z0 3) (+ z0 3))]
        (let [dx (- x x0) dy (- y y0) dz (- z z0)
              dist-sq (+ (* dx dx) (* dy dy) (* dz dz))]
          (when (and (<= dist-sq 6)
                     (or (zero? dist-sq) (< (rand) p-break)))
            (let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id x y z)
                  block-id (block-manip/get-block block-manip/*block-manipulation* world-id x y z)
                  breakable? (and (number? hardness)
                                  (>= (double hardness) 0.0)
                                  (<= (double hardness) (double hard-cap))
                                  (some? block-id)
                                  (block-manip/can-break-block?
                                   block-manip/*block-manipulation* player-id world-id x y z))]
              (when breakable?
                (block-manip/break-block! block-manip/*block-manipulation*
                                          player-id world-id x y z
                                          (or full-exp? (< (rand) p-drop)))))))))))

(defskill directed-blastwave
  :id          :directed-blastwave
  :category-id :vecmanip
  :name-key    "ability.skill.vecmanip.directed_blastwave"
  :description-key "ability.skill.vecmanip.directed_blastwave.desc"
  :icon        "textures/abilities/vecmanip/skills/dir_blast.png"
  :ui-position [136 80]
  :level       3
  :controllable? false
  :ctrl-id     :directed-blastwave
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:up {:cp       (fn [{:keys [exp]}] (cfg-lerp :cost.up.cp exp))
                     :overload (fn [{:keys [exp]}] (cfg-lerp :cost.up.overload exp))}}
  :actions
  {:down!  (fn [{:keys [ctx-id]}]
             (set-skill-state-root! ctx-id
                                    {:charge-ticks 0 :punched? false
                                     :punch-ticks 0 :performed? false})
             (fx/send-start! ctx-id :directed-blastwave/fx-start)
             (fx/send-update! ctx-id :directed-blastwave/fx-update
                              {:charge-ticks 0 :punched? false}))
   :tick!  (fn [{:keys [ctx-id]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [ss          (:skill-state ctx-data)
                     next-charge (inc (long (or (:charge-ticks ss) 0)))
                     punched?    (boolean (:punched? ss))
                     next-punch  (if punched? (inc (long (or (:punch-ticks ss) 0))) 0)]
                 (set-skill-state-root! ctx-id (assoc ss :charge-ticks next-charge :punch-ticks next-punch))
                 (fx/send-update! ctx-id :directed-blastwave/fx-update
                                  {:charge-ticks (long (max 0 next-charge))
                                   :punched? punched?})
                 (cond
                   (>= next-charge (cfg-int :charge.max-tolerant-ticks))
                   (terminate-with-end! ctx-id false)
                   (and punched? (> next-punch (cfg-int :charge.punch-anim-ticks)))
                   (terminate-with-end! ctx-id true)))))
   :up!    (fn [{:keys [player-id ctx-id exp cost-ok?]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [charge-ticks (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0))
                     exp*         (exp01 exp)]
                 (if (and (> charge-ticks (cfg-int :charge.min-ticks))
                          (< charge-ticks (cfg-int :charge.max-accepted-ticks)))
                   (if-not cost-ok?
                     (terminate-with-end! ctx-id false)
                     (let [world-id (geom/world-id-of player-id)
                           trace (when raycast/*raycast*
                                   (raycast/raycast-from-player raycast/*raycast*
                                                                player-id
                                                                (cfg-double :targeting.raycast-distance)
                                                                true))
                           hit-pos (hit-pos-from-trace player-id trace)
                           look (when raycast/*raycast*
                                  (raycast/get-player-look-vector raycast/*raycast* player-id))
                           entities (if world-effects/*world-effects*
                                      (->> (world-effects/find-entities-in-radius
                                            world-effects/*world-effects*
                                            world-id (:x hit-pos) (:y hit-pos) (:z hit-pos)
                                            (cfg-double :combat.aoe-radius))
                                           (remove #(= (:uuid %) player-id))
                                           vec)
                                      [])
                           damage (cfg-lerp :combat.damage exp*)]
                       (doseq [entity entities]
                         (when entity-damage/*entity-damage*
                           (entity-damage/apply-direct-damage!
                            entity-damage/*entity-damage* world-id (:uuid entity) damage :generic))
                         (let [knockback (knockback-impulse player-id entity)
                               push (push-impulse player-id entity)]
                           (when entity-motion/*entity-motion*
                             (entity-motion/set-velocity!
                              entity-motion/*entity-motion* world-id (:uuid entity)
                              (:x knockback) (:y knockback) (:z knockback)))
                           (when entity-motion/*entity-motion*
                             (entity-motion/add-velocity!
                              entity-motion/*entity-motion* world-id (:uuid entity)
                              (:x push) (:y push) (:z push)))))
                       (break-nearby-blocks! player-id world-id hit-pos exp*)
                       (fx/send-perform! ctx-id :directed-blastwave/fx-perform
                                         {:pos hit-pos
                                          :look-dir (or look {:x 0.0 :y 0.0 :z 1.0})
                                          :charge-ticks (long (max 0 charge-ticks))})
                        (set-skill-state-root! ctx-id
                                (assoc (:skill-state ctx-data)
                                  :punched? true :punch-ticks 0 :performed? true))
                       (skill-effects/set-main-cooldown!
                        player-id :directed-blastwave (cfg-lerp-int :cooldown.ticks exp*))
                       (skill-effects/add-skill-exp!
                        player-id :directed-blastwave (if (seq entities)
                                                        (cfg-double :progression.exp-hit)
                                                        (cfg-double :progression.exp-miss)))
                       (log/info "DirectedBlastwave executed" "charge" charge-ticks
                                 "entities" (count entities))))
                   (terminate-with-end! ctx-id false)))))
   :abort! (fn [{:keys [ctx-id]}]
             (terminate-with-end! ctx-id false))}
  :prerequisites [{:skill-id :groundshock :min-exp 0.0}])


