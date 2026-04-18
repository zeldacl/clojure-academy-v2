(ns cn.li.ac.content.ability.vecmanip.directed-blastwave
  "DirectedBlastwave - AOE blast with block breaking.

  Pattern: :charge-window (valid 6-50, abort 200)
  Cost on perform: CP lerp(160,200), overload lerp(50,30) by exp
  Cooldown: lerp(80,50) ticks by exp
  Exp: +0.0025 on hit / +0.0012 on miss"
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MIN-TICKS 6)
(def ^:private MAX-ACCEPTED-TICKS 50)
(def ^:private MAX-TOLERANT-TICKS 200)
(def ^:private RAYCAST-DISTANCE 4.0)
(def ^:private AOE-RADIUS 3.0)
(def ^:private PUNCH-ANIM-TICKS 6)

(defn- hit-pos-from-trace [player-id trace]
  (let [eye  (geom/eye-pos player-id)
        look (or (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))
                 {:x 0.0 :y 0.0 :z 1.0})]
    (cond
      (nil? trace)
      (geom/v+ eye (geom/v* (geom/vnorm look) RAYCAST-DISTANCE))
      (= :block (:hit-type trace))
      {:x (Math/floor (double (or (:x trace) 0.0)))
       :y (Math/floor (double (or (:y trace) 0.0)))
       :z (Math/floor (double (or (:z trace) 0.0)))}
      (= :entity (:hit-type trace))
      {:x (double (or (:x trace) 0.0))
       :y (+ (double (or (:y trace) 0.0)) (double (or (:eye-height trace) 1.62)))
       :z (double (or (:z trace) 0.0))}
      :else
      {:x (double (or (:x trace) 0.0))
       :y (double (or (:y trace) 0.0))
       :z (double (or (:z trace) 0.0))})))

(defn- knockback-impulse [player-id entity]
  (let [player-head (geom/eye-pos player-id)
        target-head {:x (double (or (:x entity) 0.0))
                     :y (+ (double (or (:y entity) 0.0)) (double (or (:eye-height entity) 1.62)))
                     :z (double (or (:z entity) 0.0))}
        d0 (geom/vnorm (geom/v- player-head target-head))
        d1 (geom/vnorm {:x (:x d0) :y (- (:y d0) 0.4) :z (:z d0)})]
    (geom/v* d1 -1.2)))

(defn- break-hardness [exp]
  (cond (< (double exp) 0.25) 2.9
        (< (double exp) 0.5)  25.0
        :else                 55.0))

(defn- break-nearby-blocks! [player-id world-id pos exp]
  (when block-manip/*block-manipulation*
    (let [x0        (int (Math/floor (double (:x pos))))
          y0        (int (Math/floor (double (:y pos))))
          z0        (int (Math/floor (double (:z pos))))
          hard-cap  (break-hardness exp)
          p-break   (bal/lerp 0.5 0.8 exp)
          p-drop    (bal/lerp 0.4 0.9 exp)
          full-exp? (= 1.0 (double (bal/clamp01 exp)))]
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

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-start {:mode :start}))
(defn- send-fx-update! [ctx-id charge-ticks punched?]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-update
                           {:mode :update :charge-ticks (long (max 0 charge-ticks))
                            :punched? (boolean punched?)}))
(defn- send-fx-perform! [ctx-id hit-pos charge-ticks look]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-perform
                           {:mode :perform :pos hit-pos
                            :look-dir (or look {:x 0.0 :y 0.0 :z 1.0})
                            :charge-ticks (long (max 0 charge-ticks))}))
(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-end
                           {:mode :end :performed? (boolean performed?)}))

(defskill! directed-blastwave
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
  :cost        {:up {:cp       (fn [{:keys [exp]}] (bal/lerp 160.0 200.0 (bal/clamp01 exp)))
                     :overload (fn [{:keys [exp]}] (bal/lerp 50.0   30.0 (bal/clamp01 exp)))}}
  :actions
  {:down!  (fn [{:keys [ctx-id]}]
             (ctx/update-context! ctx-id assoc :skill-state
                                  {:charge-ticks 0 :punched? false
                                   :punch-ticks 0  :performed? false})
             (send-fx-start! ctx-id)
             (send-fx-update! ctx-id 0 false))
   :tick!  (fn [{:keys [ctx-id]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [ss           (:skill-state ctx-data)
                     next-charge  (inc (long (or (:charge-ticks ss) 0)))
                     punched?     (boolean (:punched? ss))
                     next-punch   (if punched? (inc (long (or (:punch-ticks ss) 0))) 0)]
                 (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
                 (ctx/update-context! ctx-id assoc-in [:skill-state :punch-ticks]  next-punch)
                 (send-fx-update! ctx-id next-charge punched?)
                 (cond
                   (>= next-charge MAX-TOLERANT-TICKS)
                   (do (send-fx-end! ctx-id false) (ctx/terminate-context! ctx-id nil))
                   (and punched? (> next-punch PUNCH-ANIM-TICKS))
                   (do (send-fx-end! ctx-id true) (ctx/terminate-context! ctx-id nil))))))
   :up!    (fn [{:keys [player-id ctx-id exp cost-ok?]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [charge-ticks (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0))
                     exp*         (bal/clamp01 exp)]
                 (if (and (> charge-ticks MIN-TICKS) (< charge-ticks MAX-ACCEPTED-TICKS))
                   (if-not cost-ok?
                     (do (send-fx-end! ctx-id false)
                         (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))
                     (let [world-id   (geom/world-id-of player-id)
                           trace      (when raycast/*raycast*
                                        (raycast/raycast-from-player raycast/*raycast*
                                                                     player-id RAYCAST-DISTANCE true))
                           hit-pos    (hit-pos-from-trace player-id trace)
                           look       (when raycast/*raycast*
                                        (raycast/get-player-look-vector raycast/*raycast* player-id))
                           entities   (if world-effects/*world-effects*
                                        (->> (world-effects/find-entities-in-radius
                                               world-effects/*world-effects*
                                               world-id (:x hit-pos) (:y hit-pos) (:z hit-pos)
                                               AOE-RADIUS)
                                             (remove #(= (:uuid %) player-id))
                                             vec)
                                        [])
                           damage     (bal/lerp 10.0 25.0 exp*)]
                       (doseq [entity entities]
                         (when entity-damage/*entity-damage*
                           (entity-damage/apply-direct-damage!
                            entity-damage/*entity-damage* world-id (:uuid entity) damage :generic))
                         (let [impulse (knockback-impulse player-id entity)]
                           (when entity-motion/*entity-motion*
                             (entity-motion/add-velocity!
                              entity-motion/*entity-motion* world-id (:uuid entity)
                              (:x impulse) (:y impulse) (:z impulse)))))
                       (break-nearby-blocks! player-id world-id hit-pos exp*)
                       (send-fx-perform! ctx-id hit-pos charge-ticks look)
                       (ctx/update-context! ctx-id update :skill-state assoc
                                            :punched? true :punch-ticks 0 :performed? true)
                       (skill-effects/set-main-cooldown!
                        player-id :directed-blastwave (int (bal/lerp 80.0 50.0 exp*)))
                       (skill-effects/add-skill-exp!
                        player-id :directed-blastwave (if (seq entities) 0.0025 0.0012))
                       (log/info "DirectedBlastwave executed" "charge" charge-ticks
                                 "entities" (count entities))))
                   (do (send-fx-end! ctx-id false)
                       (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))))))
   :abort! (fn [{:keys [ctx-id]}]
             (send-fx-end! ctx-id false)
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :groundshock :min-exp 0.5}])
