(ns cn.li.ac.content.ability.electromaster.mag-manip
  "MagManip skill - grab a magnetizable block, hold it, throw it on key release.

  Pattern: :release-cast
  Cost:     CP lerp(140,270) / overload lerp(35,20) - only charged when holding block nearby
  Cooldown: manual, lerp(60,40) ticks by exp
  Exp:      +0.005 on successful throw"
  (:require [clojure.string :as str]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.effect.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; --- Constants ---

(def ^:private max-grab-range 10.0)
(def ^:private max-throw-range 20.0)
(def ^:private hold-distance 2.0)
;; Original: subtract(entityHeadPos, new Vec3d(0, 0.1, 0)) -> -0.1 on Y
(def ^:private hold-head-y-offset 0.1)
(def ^:private throw-hit-radius 1.15)

(def ^:private strong-metal-blocks
  #{"minecraft:iron_block"
    "minecraft:iron_ore"
    "minecraft:deepslate_iron_ore"
    "minecraft:gold_block"
    "minecraft:gold_ore"
    "minecraft:deepslate_gold_ore"
    "minecraft:copper_block"
    "minecraft:copper_ore"
    "minecraft:deepslate_copper_ore"
    "minecraft:netherite_block"
    "minecraft:ancient_debris"
    "minecraft:anvil"
    "minecraft:chipped_anvil"
    "minecraft:damaged_anvil"
    "minecraft:hopper"
    "minecraft:chain"
    "minecraft:iron_bars"
    "minecraft:iron_door"
    "minecraft:iron_trapdoor"
    "minecraft:rail"
    "minecraft:powered_rail"
    "minecraft:detector_rail"
    "minecraft:activator_rail"})

(def ^:private weak-metal-hints
  ["iron" "gold" "copper" "rail" "anvil" "chain" "ore" "debris" "metal" "steel"])

;; --- Domain helpers ---

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :mag-manip :exp] 0.0)))

(defn- metal-block-id? [block-id exp]
  (let [id (some-> block-id str/lower-case)]
    (boolean
      (or (contains? strong-metal-blocks id)
          (and (>= (double exp) 0.5)
               (some #(str/includes? id %) weak-metal-hints))))))

(defn- look-dir [player-id]
  (when-let [look (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (geom/vnorm {:x (double (:x look))
                 :y (double (:y look))
                 :z (double (:z look))})))

;; origin = eye - (0, hold-head-y-offset, 0); focus = origin + look * hold-distance
(defn- hold-focus [player-id]
  (let [eye (geom/eye-pos player-id)
        origin (geom/v+ eye {:x 0.0 :y (- hold-head-y-offset) :z 0.0})
        dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})]
    (geom/v+ origin (geom/v* dir hold-distance))))

;; --- Block-manipulation helpers ---

(defn- restore-held-block! [{:keys [from-world? source-x source-y source-z block-id world-id]}]
  (when (and from-world?
             block-manip/*block-manipulation*
             block-id
             (number? source-x)
             (number? source-y)
             (number? source-z))
    (let [bx (int source-x)
          by (int source-y)
          bz (int source-z)
          current (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)]
      (when (or (nil? current) (= current "minecraft:air"))
        (block-manip/set-block! block-manip/*block-manipulation* world-id bx by bz block-id)))))

(defn- pick-up-target-block [player-id exp]
  (when (and raycast/*raycast* block-manip/*block-manipulation*)
    (when-let [dir (look-dir player-id)]
      (let [start (geom/eye-pos player-id)
            world-id (geom/world-id-of player-id)
            hit (raycast/raycast-blocks raycast/*raycast*
                                        world-id
                                        (:x start) (:y start) (:z start)
                                        (:x dir) (:y dir) (:z dir)
                                        max-grab-range)]
        (when hit
          (let [bx (int (or (:x hit) 0))
                by (int (or (:y hit) 0))
                bz (int (or (:z hit) 0))
                block-id (or (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)
                             (:block-id hit))
                hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id bx by bz)]
            (when (and (string? block-id)
                       (metal-block-id? block-id exp)
                       (number? hardness)
                       (not (neg? (double hardness))))
              {:world-id world-id
               :x bx :y by :z bz
               :block-id block-id
               :hardness hardness})))))))

(defn- distance-to-segment [point seg-start seg-end]
  (let [ab (geom/v- seg-end seg-start)
        ap (geom/v- point seg-start)
        denom (max 1.0e-6 (geom/vdot ab ab))
        t (max 0.0 (min 1.0 (/ (geom/vdot ap ab) denom)))
        closest (geom/v+ seg-start (geom/v* ab t))]
    {:distance (geom/vlen (geom/v- point closest))
     :t t}))

(defn- try-place-thrown-block! [world-id end-pos held-block-id]
  (when (and block-manip/*block-manipulation* world-id held-block-id)
    (let [bx (geom/floor-int (:x end-pos))
          by (geom/floor-int (:y end-pos))
          bz (geom/floor-int (:z end-pos))
          current (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)]
      (when (or (nil? current) (= current "minecraft:air"))
        (block-manip/set-block! block-manip/*block-manipulation* world-id bx by bz held-block-id)
        true))))

;; --- Cost helpers ---

(defn- holding-nearby? [player-id ctx-id]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)]
      (when (and (= :holding (:mode ss)) (:held-block ss))
        (let [focus (or (:focus ss) (hold-focus player-id))
              pos (get (ps/get-player-state player-id) :position {:x 0.0 :y 0.0 :z 0.0})]
          (< (geom/vdist-sq pos focus) 25.0))))))

(defn- cost-up-cp [{:keys [player-id ctx-id]}]
  (if (holding-nearby? player-id ctx-id)
    (bal/lerp 140.0 270.0 (skill-exp player-id))
    0.0))

(defn- cost-up-overload [{:keys [player-id ctx-id]}]
  (if (holding-nearby? player-id ctx-id)
    (bal/lerp 35.0 20.0 (skill-exp player-id))
    0.0))

(defn- cost-creative? [{:keys [player]}]
  (boolean (and player (entity/player-creative? player))))

;; --- Action hooks ---

;; Original s_makeAlive: check hand item first, fallback to raycast world block,
;; if neither found store :no-target mode.
(defn- on-down [{:keys [player-id ctx-id player]}]
  (let [exp (skill-exp player-id)
        world-id (geom/world-id-of player-id)
        hand-item-id (when player (entity/player-get-main-hand-item-id player))
        held-metal? (and (string? hand-item-id) (metal-block-id? hand-item-id exp))]
    (cond
      held-metal?
      (let [creative? (boolean (and player (entity/player-creative? player)))
            _ (when-not creative? (entity/player-consume-main-hand-item! player 1))
            focus (hold-focus player-id)]
        (ctx/update-context! ctx-id assoc :skill-state
                             {:fired false
                              :mode :holding
                              :hold-ticks 0
                              :held-block {:block-id hand-item-id
                                           :from-world? false
                                           :from-hand? true
                                           :world-id world-id}
                              :focus focus})
        (ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
                                 {:mode :hold-start
                                  :focus focus
                                  :block-id hand-item-id}))

      :else
      (if-let [{:keys [world-id x y z block-id]} (pick-up-target-block player-id exp)]
        (do
          (when (block-manip/can-break-block? block-manip/*block-manipulation* player-id world-id x y z)
            (block-manip/break-block! block-manip/*block-manipulation* player-id world-id x y z false))
          (let [focus (hold-focus player-id)]
            (ctx/update-context! ctx-id assoc :skill-state
                                 {:fired false
                                  :mode :holding
                                  :hold-ticks 0
                                  :held-block {:block-id block-id
                                               :from-world? true
                                               :world-id world-id
                                               :source-x x
                                               :source-y y
                                               :source-z z}
                                  :focus focus})
            (ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
                                     {:mode :hold-start
                                      :focus focus
                                      :block-id block-id})))
        (ctx/update-context! ctx-id assoc :skill-state
                             {:fired false
                              :mode :no-target})))))

;; Original s_tick: update hold position, send FX every 2 ticks.
(defn- on-tick [{:keys [player-id ctx-id]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)]
      (when (= :holding (:mode ss))
        (let [ticks (inc (int (or (:hold-ticks ss) 0)))
              focus (hold-focus player-id)]
          (ctx/update-context! ctx-id assoc-in [:skill-state :hold-ticks] ticks)
          (ctx/update-context! ctx-id assoc-in [:skill-state :focus] focus)
          (when (zero? (mod ticks 2))
            (ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
                                     {:mode :hold-loop
                                      :focus focus
                                      :block-id (get-in ss [:held-block :block-id])})))))))

;; Original s_perform: check dist < 5 blocks, throw, damage always 10.
;; Cooldown and exp applied manually - only on successful throw.
(defn- on-up [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)
          held-block (get ss :held-block)
          exp (skill-exp player-id)]
      (if-not (and (= :holding (:mode ss)) held-block)
        (ctx/update-context! ctx-id assoc :skill-state
                             (assoc ss :fired false :mode :idle))
        (let [focus (or (:focus ss) (hold-focus player-id))
              pos (get (ps/get-player-state player-id) :position {:x 0.0 :y 0.0 :z 0.0})
              too-far? (>= (geom/vdist-sq pos focus) 25.0)]
          (if too-far?
            (do
              (restore-held-block! held-block)
              (ctx/update-context! ctx-id assoc :skill-state
                                   (assoc ss :fired false :mode :too-far)))
            (if-not cost-ok?
              (do
                (restore-held-block! held-block)
                (ctx/update-context! ctx-id assoc :skill-state
                                     (assoc ss :fired false :mode :no-resource)))
              (let [world-id (geom/world-id-of player-id)
                    start focus
                    dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})
                    hit (when raycast/*raycast*
                          (raycast/raycast-combined raycast/*raycast*
                                                    world-id
                                                    (:x start) (:y start) (:z start)
                                                    (:x dir) (:y dir) (:z dir)
                                                    max-throw-range))
                    end (if hit
                          {:x (double (:x hit)) :y (double (:y hit)) :z (double (:z hit))}
                          (geom/v+ start (geom/v* dir max-throw-range)))
                    damage 10.0
                    direct-hit? (and (= (:hit-type hit) :entity)
                                     (:uuid hit)
                                     entity-damage/*entity-damage*)]
                (if direct-hit?
                  (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                      world-id (:uuid hit) damage :magic)
                  (when (and world-effects/*world-effects* entity-damage/*entity-damage*)
                    (let [mid (geom/v* (geom/v+ start end) 0.5)
                          radius (+ (* 0.5 (geom/vlen (geom/v- end start))) 2.0)
                          entities (sort-by :uuid
                                            (remove #(= (:uuid %) player-id)
                                                    (world-effects/find-entities-in-radius
                                                     world-effects/*world-effects*
                                                     world-id
                                                     (:x mid) (:y mid) (:z mid)
                                                     radius)))]
                      (when-let [target (first
                                         (filter (fn [{:keys [x y z]}]
                                                   (let [{:keys [distance t]} (distance-to-segment {:x x :y y :z z} start end)]
                                                     (and (<= distance throw-hit-radius)
                                                          (<= 0.0 t 1.0))))
                                                 entities))]
                        (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                            world-id (:uuid target) damage :magic)))))
                (when (= (:hit-type hit) :block)
                  (try-place-thrown-block! world-id end (:block-id held-block)))
                (ctx/ctx-send-to-client! ctx-id :mag-manip/fx-throw
                                         {:mode :throw
                                          :start start
                                          :end end
                                          :hit-type (:hit-type hit)
                                          :block-id (:block-id held-block)})
                ;; Manual cooldown and exp - only on successful throw
                (skill-effects/set-main-cooldown! player-id :mag-manip
                                                  (int (Math/round ^double (bal/lerp 60.0 40.0 exp))))
                (skill-effects/add-skill-exp! player-id :mag-manip 0.005)
                (ctx/update-context! ctx-id assoc :skill-state
                                     {:fired true
                                      :mode :thrown
                                      :held-block nil})))))))))

(defn- on-abort [{:keys [ctx-id]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (when-let [held (get-in ctx-data [:skill-state :held-block])]
      (restore-held-block! held))
    (ctx/update-context! ctx-id dissoc :skill-state)))

;; --- Skill definition ---

(defskill! mag-manip
  :id :mag-manip
  :category-id :electromaster
  :name-key "ability.skill.electromaster.mag_manip"
  :description-key "ability.skill.electromaster.mag_manip.desc"
  :icon "textures/abilities/electromaster/skills/mag_manip.png"
  :ui-position [204 33]
  :level 3
  :controllable? true
  :ctrl-id :mag-manip
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp cost-up-cp
              :overload cost-up-overload
              :creative? cost-creative?}}
  :actions {:down! on-down
            :tick! on-tick
            :up! on-up
            :abort! on-abort}
  :prerequisites [{:skill-id :mag-movement :min-exp 0.5}])