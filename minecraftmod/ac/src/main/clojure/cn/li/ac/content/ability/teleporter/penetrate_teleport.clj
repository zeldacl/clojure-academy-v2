(ns cn.li.ac.content.ability.teleporter.penetrate-teleport
  "PenetrateTP skill - teleport through solid walls to open space.

  Pattern: :release-cast
  Marches along look direction through solid blocks until finding
  an air pocket large enough for the player to stand in.
  Max penetration depth: lerp(3, 6, exp) blocks inside wall
  Scan distance: up to 30 blocks
  CP cost: lerp(200, 140, exp)
  Overload: lerp(80, 55, exp)
  Cooldown: lerp(40, 25, exp) ticks
  Exp: +0.003 per success

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Wall-penetration scan
;; ---------------------------------------------------------------------------

(defn- solid? [world-id bx by bz]
  (when bm/*block-manipulation*
    (let [h (bm/get-block-hardness bm/*block-manipulation* world-id bx by bz)]
      (and (some? h) (pos? (double h))))))

(defn- air? [world-id bx by bz]
  (not (solid? world-id bx by bz)))

(defn- can-stand? [world-id bx by bz]
  "Is there a 1x2 air gap at [bx by bz] with solid floor below?"
  (and (air? world-id bx by bz)
       (air? world-id bx (+ by 1) bz)))

(defn- find-penetrate-destination
  "March along look dir. Returns {:x :y :z} in blocks (bottom of player), or nil."
  [player-id look-vec max-depth scan-dist]
  (when (and look-vec bm/*block-manipulation*)
    (let [world-id (geom/world-id-of player-id)
          player-pos (helper/player-position player-id)]
      (when player-pos
        (let [px (double (:x player-pos))
              py (double (:y player-pos))
              pz (double (:z player-pos))
              dx (double (:x look-vec))
              dy (double (:y look-vec))
              dz (double (:z look-vec))
              ;; Step 0.5 blocks at a time
              steps (int (* (double scan-dist) 2))]
          (loop [i 0
                 inside-wall? false
                 wall-start-i nil]
            (when (< i steps)
              (let [t   (* (double i) 0.5)
                    cx  (int (Math/floor (+ px (* t dx))))
                    cy  (int (Math/floor (+ py (* t dy))))
                    cz  (int (Math/floor (+ pz (* t dz))))
                    sol (solid? world-id cx cy cz)]
                (cond
                  ;; Found air after being in wall
                  (and inside-wall? (not sol))
                  (when (can-stand? world-id cx cy cz)
                    {:x (+ cx 0.5) :y (double cy) :z (+ cz 0.5)})
                  ;; Entered wall
                  (and (not inside-wall?) sol)
                  (recur (inc i) true i)
                  ;; Inside wall: check max depth
                  (and inside-wall? sol)
                  (if (> (- i (long (or wall-start-i i))) (* 2 (double max-depth)))
                    nil  ; too deep
                    (recur (inc i) true wall-start-i))
                  ;; Still in air before wall
                  :else
                  (recur (inc i) false nil)))))))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn penetrate-tp-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:ticks 0})))

(defn penetrate-tp-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] hold-ticks))

(defn penetrate-tp-up!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp       (helper/skill-exp player-id :penetrate-teleport)
          max-depth (bal/lerp 3.0 6.0 exp)
          look-vec  (helper/player-look-vec player-id)
          dest      (find-penetrate-destination player-id look-vec max-depth 30.0)]
      (if dest
        (let [world-id (geom/world-id-of player-id)]
          (when (helper/teleport-to! player-id world-id (:x dest) (:y dest) (:z dest))
            (skill-effects/add-skill-exp! player-id :penetrate-teleport 0.003)
            (let [cd (int (bal/lerp 40.0 25.0 exp))]
              (skill-effects/set-main-cooldown! player-id :penetrate-teleport cd))
            (ctx/ctx-send-to-client! ctx-id :penetrate-tp/fx-perform dest)))
        (log/debug "PenetrateTP: no valid destination found")))
    (catch Exception e
      (log/warn "PenetrateTP up! failed:" (ex-message e)))))

(defn penetrate-tp-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! penetrate-teleport
  :id             :penetrate-teleport
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.penetrate_teleport"
  :description-key "ability.skill.teleporter.penetrate_teleport.desc"
  :icon           "textures/abilities/teleporter/skills/penetrate_teleport.png"
  :ui-position    [60 160]
  :level          3
  :controllable?  true
  :ctrl-id        :penetrate-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 200.0 140.0
                                                (helper/skill-exp player-id :penetrate-teleport)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 80.0 55.0
                                                (helper/skill-exp player-id :penetrate-teleport)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  penetrate-tp-down!
                   :tick!  penetrate-tp-tick!
                   :up!    penetrate-tp-up!
                   :abort! penetrate-tp-abort!}
  :fx             {:start {:topic :penetrate-tp/fx-start :payload (fn [_] {})}
                   :end   {:topic :penetrate-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mark-teleport :min-exp 0.8}])
