(ns cn.li.ac.content.ability.electromaster.mag-manip
  "MagManip skill - grab a magnetizable block, hold it, throw it on key release.

  Pattern: :release-cast
  Cost:     CP lerp(140,270) / overload lerp(35,20) - only charged when holding block nearby
  Cooldown: manual, lerp(60,40) ticks by exp
  Exp:      +0.005 on successful throw

  Matches original AcademyCraft MagManipContext: the held/thrown block is a
  real physics entity (entity_magmanip_block_body) that homes toward the
  player's aim while held and gets a one-shot velocity on throw — damage and
  block placement both come from the entity's own real collision (see
  ScriptedBlockBodyEntity.onHitEntity/onHit), not from instant hit-scan math."
  (:require
            [cn.li.ac.config.modid :as modid] [clojure.string :as str]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; --- Constants ---

(def-skill-config-ops :mag-manip)
(def ^:private mag-manip-skill-id :mag-manip)
(def ^:private tracked-block-body-entity-id (modid/namespaced-path "entity_magmanip_block_body"))

(defn- strong-metal-blocks []
  (set (map str/lower-case
            (skill-config/tunable-string-list mag-manip-skill-id
                                              :targeting.strong-metal-blocks))))

(defn- weak-metal-hints []
  (map str/lower-case
       (skill-config/tunable-string-list mag-manip-skill-id
                                         :targeting.weak-metal-keywords)))

(defn- max-hold-distance-sq []
  (let [distance (cfg-double :targeting.max-hold-distance)]
    (* distance distance)))

;; --- Domain helpers ---

;; Matches original CatElectromaster#isMetalBlock: two fixed lists, no
;; progression gate on either.
(defn- metal-block-id? [block-id]
  (let [id (some-> block-id str/lower-case)]
    (boolean
      (and id
           (or (contains? (strong-metal-blocks) id)
               (some #(str/includes? id %) (weak-metal-hints)))))))

(defn- look-dir [player-id]
  (when-let [look (when (raycast/available?)
                    (raycast/player-look-vector player-id))]
    (geom/vnorm {:x (double (:x look))
                 :y (double (:y look))
                 :z (double (:z look))})))

;; origin = eye - (0, hold-head-y-offset, 0); focus = origin + look * hold-distance
(defn- hold-focus [player-id]
  (let [eye (geom/eye-pos player-id)
        origin (geom/v+ eye {:x 0.0 :y (- (cfg-double :movement.hold-head-y-offset)) :z 0.0})
        dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})]
    (geom/v+ origin (geom/v* dir (cfg-double :movement.hold-distance)))))

;; --- Held-entity homing (matches original MagManipEntityBlock's ActMoveTo) ---

(defn- home-entity-toward!
  "Push one tick of homing velocity toward `target` — exact original formula:
  motion = normalize(target - pos) * 0.2 * (distSq < 4 ? distSq/4 : 1.0)."
  [world-id entity-uuid target]
  (when-let [pos (motion/entity-position world-id entity-uuid)]
    (let [delta (geom/v- target pos)
          len (geom/vlen delta)]
      (when (pos? len)
        (let [dist-sq (geom/vdist-sq pos target)
              scale (* 0.2 (if (< dist-sq 4.0) (/ dist-sq 4.0) 1.0))
              mv (geom/v* (geom/vnorm delta) scale)]
          (motion/set-entity-velocity! world-id entity-uuid (:x mv) (:y mv) (:z mv)))))))

;; --- Block-manipulation helpers ---

(defn- pick-up-target-block [player-id]
  (when (and (raycast/available?) (block-manip/available?))
    (when-let [dir (look-dir player-id)]
      (let [start (geom/eye-pos player-id)
            world-id (geom/world-id-of player-id)
            hit (raycast/raycast-blocks
                                        world-id
                                        (:x start) (:y start) (:z start)
                                        (:x dir) (:y dir) (:z dir)
                                        (cfg-double :targeting.grab-range))]
        (when hit
          (let [bx (int (or (:x hit) 0))
                by (int (or (:y hit) 0))
                bz (int (or (:z hit) 0))
                block-id (or (block-manip/get-block world-id bx by bz)
                             (:block-id hit))
                hardness (block-manip/get-block-hardness world-id bx by bz)]
            (when (and (string? block-id)
                       (metal-block-id? block-id)
                       (number? hardness)
                       (not (neg? (double hardness))))
              {:world-id world-id
               :x bx :y by :z bz
               :block-id block-id
               :hardness hardness})))))))

;; --- Cost helpers ---

(defn- holding-nearby? [player-id ctx-id]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)]
      (when (and (= :holding (:mode ss)) (:held-block ss))
        (let [focus (or (:focus ss) (hold-focus player-id))
                pos (skill-effects/player-path player-id :position {:x 0.0 :y 0.0 :z 0.0})]
                  (< (geom/vdist-sq pos focus) (max-hold-distance-sq)))))))

(defn- active-ctx-id [player-id skill-id]
  (some (fn [[ctx-id ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (= skill-id (:skill-id ctx-data)))
            ctx-id))
        (ctx/get-all-contexts)))

(defn- cost-up-cp [player-id _skill-id exp]
  (if-let [ctx-id (active-ctx-id player-id mag-manip-skill-id)]
    (if (holding-nearby? player-id ctx-id)
      (cfg-lerp :cost.up.cp exp)
      0.0)
    0.0))

(defn- cost-up-overload [player-id _skill-id exp]
  (if-let [ctx-id (active-ctx-id player-id mag-manip-skill-id)]
    (if (holding-nearby? player-id ctx-id)
      (cfg-lerp :cost.up.overload exp)
      0.0)
    0.0))

(defn- cost-creative? [_player-id _skill-id _exp]
  false)

;; --- Action hooks ---

;; Original s_makeAlive: check hand item first, fallback to raycast world block,
;; if neither found store :no-target mode. Spawns a real tracked physics
;; entity (matching original's new MagManipEntityBlock(player, 10)) instead of
;; a fire-and-forget cosmetic one — held/thrown behavior now rides on its real
;; collision via ScriptedBlockBodyEntity.
(defn- start-holding! [ctx-id player-id player held-block]
  (let [focus (hold-focus player-id)
        entity-uuid (when player
                      (entity/player-spawn-tracked-entity-by-id!
                        player tracked-block-body-entity-id 0.0))]
    (ctx-skill/replace-skill-state! ctx-id
                           {:fired false
                            :mode :holding
                            :hold-ticks 0
                            :held-block held-block
                            :focus focus
                            :entity-uuid entity-uuid
                            :world-id (:world-id held-block)})
    (fx/send! ctx-id {:topic :mag-manip/fx-hold :mode :hold-start} nil
              {:focus focus
               :block-id (:block-id held-block)})))

;; Original s_makeAlive: check hand item first, fallback to raycast world block,
;; if neither found store :no-target mode.
(defn- on-down
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage player]
  (let [world-id (geom/world-id-of player-id)
        hand-item-id (when player (entity/player-get-main-hand-item-id player))
        held-metal? (and (string? hand-item-id) (metal-block-id? hand-item-id))]
    (if held-metal?
      (let [creative? (boolean (and player (entity/player-creative? player)))
            consumed? (or creative?
                          (and player (entity/player-consume-main-hand-item! player 1)))]
        (if consumed?
          (start-holding! ctx-id player-id player {:block-id hand-item-id
                                            :from-world? false
                                            :from-hand? true
                                            :world-id world-id})
          (ctx-skill/replace-skill-state! ctx-id
                                 {:fired false
                                  :mode :capture-failed})))
      (if-let [{:keys [world-id x y z block-id]} (pick-up-target-block player-id)]
        (let [can-break? (block-manip/can-break-block? player-id world-id x y z)
              broken? (and can-break?
                           (block-manip/break-block! player-id world-id x y z false))]
          (if broken?
            (start-holding! ctx-id player-id player {:block-id block-id
                                              :from-world? true
                                              :world-id world-id
                                              :source-x x
                                              :source-y y
                                              :source-z z})
            (do
              (log/debug "MagManip capture failed" {:world-id world-id :x x :y y :z z :block-id block-id})
              (ctx-skill/replace-skill-state! ctx-id
                                     {:fired false
                                      :mode :capture-failed}))))
        (ctx-skill/replace-skill-state! ctx-id
                               {:fired false
                                :mode :no-target})))))

;; Original s_tick: update hold position (both ends ran updateMoveTo() every
;; tick) — here the server pushes fresh homing velocity to the real entity
;; every tick, and FX is still sent every 2 ticks for the client visuals.
(defn- on-tick
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)]
      (when (= :holding (:mode ss))
        (let [ticks (inc (int (or (:hold-ticks ss) 0)))
              focus (hold-focus player-id)
              entity-uuid (:entity-uuid ss)
              world-id (:world-id ss)]
          (ctx-skill/replace-skill-state! ctx-id
                                 (assoc ss :hold-ticks ticks :focus focus))
          (when (and entity-uuid world-id (motion/entity-motion-available?))
            (home-entity-toward! world-id entity-uuid focus))
          (when (zero? (mod ticks 2))
            (fx/send! ctx-id {:topic :mag-manip/fx-hold :mode :hold-loop} nil
                      {:focus focus
                       :block-id (get-in ss [:held-block :block-id])})))))))

;; Original s_perform: check dist < 5 blocks, consume resources, then give the
;; entity a one-shot velocity toward the looked-at point (raytrace 20 blocks)
;; at lerp(0.5,1.0,exp) speed. Damage and block placement are no longer done
;; here — they happen via the entity's own real collision once it's flying.
;; On failure (too far / no resource) the entity is simply no longer homed —
;; it falls under its own gravity and self-places wherever it lands, matching
;; original's ActNothing fallback (no rollback to the player).
(defn- on-up
  [ctx-id player-id _skill-id exp cost-ok? _hold-ticks _cost-stage _player]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (let [ss (:skill-state ctx-data)
          held-block (get ss :held-block)
          entity-uuid (:entity-uuid ss)
          world-id (:world-id ss)]
      (if-not (and (= :holding (:mode ss)) held-block)
        (ctx-skill/replace-skill-state! ctx-id
                               (assoc ss :fired false :mode :idle))
        (let [focus (or (:focus ss) (hold-focus player-id))
              pos (skill-effects/player-path player-id :position {:x 0.0 :y 0.0 :z 0.0})
              too-far? (>= (geom/vdist-sq pos focus) (max-hold-distance-sq))]
          (cond
            too-far?
            (do
              (fx/send! ctx-id {:topic :mag-manip/fx-end :mode :end} nil {:reason :too-far})
              (ctx-skill/replace-skill-state! ctx-id
                                     (assoc ss :fired false :mode :too-far)))

            (not cost-ok?)
            (do
              (fx/send! ctx-id {:topic :mag-manip/fx-end :mode :end} nil {:reason :no-resource})
              (ctx-skill/replace-skill-state! ctx-id
                                     (assoc ss :fired false :mode :no-resource)))

            :else
            (let [dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})
                  hit (when (raycast/available?)
                        (raycast/raycast-combined
                                                  world-id
                                                  (:x focus) (:y focus) (:z focus)
                                                  (:x dir) (:y dir) (:z dir)
                                                  (cfg-double :targeting.throw-range)))
                  end (if hit
                        {:x (double (:x hit)) :y (double (:y hit)) :z (double (:z hit))}
                        (geom/v+ focus (geom/v* dir (cfg-double :targeting.throw-range))))
                  speed (cfg-lerp :movement.throw-speed exp)
                  entity-pos (when (and entity-uuid world-id) (motion/entity-position world-id entity-uuid))
                  throw-origin (or entity-pos focus)
                  delta (geom/v- end throw-origin)
                  throw-dir (if (pos? (geom/vlen delta)) (geom/vnorm delta) dir)]
              (when (and entity-uuid world-id (motion/entity-motion-available?))
                (let [vel (geom/v* throw-dir speed)]
                  (motion/set-entity-velocity! world-id entity-uuid (:x vel) (:y vel) (:z vel))))

              (fx/send! ctx-id {:topic :mag-manip/fx-throw :mode :throw} nil
                        {:start throw-origin
                         :end end
                         :hit-type (:hit-type hit)
                         :block-id (:block-id held-block)})
              (fx/send! ctx-id {:topic :mag-manip/fx-end :mode :end} nil {:reason :performed})

              ;; Manual cooldown and exp - only on successful throw
              (skill-effects/set-main-cooldown! player-id mag-manip-skill-id
                                                (skill-config/lerp-int mag-manip-skill-id
                                                                       :cooldown.ticks
                                                                       exp))
              (skill-effects/add-skill-exp! player-id mag-manip-skill-id
                                            (cfg-double :progression.exp-throw))
              (ctx-skill/replace-skill-state! ctx-id
                                     {:fired true
                                      :mode :thrown
                                      :held-block nil}))))))))

(defn- on-abort
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (when (get-in ctx-data [:skill-state :held-block])
      (fx/send! ctx-id {:topic :mag-manip/fx-end :mode :end} nil {:reason :abort}))
    (ctx-skill/clear-skill-state! ctx-id)))

;; --- Skill definition ---

(declare mag-manip)

(defskill mag-manip
  :id :mag-manip
  :category-id :electromaster
  :name-key "ability.skill.electromaster.mag_manip"
  :description-key "ability.skill.electromaster.mag_manip.desc"
  :icon "textures/abilities/electromaster/skills/mag_manip.png"
  :ui-position [204 33]
  :ctrl-id :mag-manip
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (skill-config/lerp-int mag-manip-skill-id
                                           :cooldown.ticks
                                           (skill-exp player-id)))
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
