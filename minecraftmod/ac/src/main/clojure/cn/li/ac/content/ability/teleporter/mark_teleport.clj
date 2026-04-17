(ns cn.li.ac.content.ability.teleporter.mark-teleport
  "MarkTeleport skill - teleport to look direction target.

  Original-aligned mechanics:
  - Hold key to extend range by 2 blocks per tick up to min(max-range, current-cp/cpb)
  - Max range: lerp(25,60,exp)
  - CP consume per block: lerp(12,4,exp)
  - Overload: lerp(40,20,exp)
  - Cooldown: lerp(30,0,exp)
  - Minimum valid distance: 3 blocks
  - Missed raycasts still target look-direction endpoint
  - Release teleports, dismounts riding entities, resets fall damage
  - Experience gain: 0.00018 脳 distance
  - Client-side destination marker with looping teleport particles and execute sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log]))

(def ^:private minimum-valid-distance 3.0)
(def ^:private eye-height 1.6)

(defn- current-cp [player-id]
  (double (or (get-in (ps/get-player-state player-id) [:resource-data :cur-cp]) 0.0)))

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :mark-teleport :exp] 0.0)))

(defn- cp-per-block [exp]
  (bal/lerp 12.0 4.0 exp))

(defn- overload-cost [exp]
  (bal/lerp 40.0 20.0 exp))

(defn- cooldown-ticks [exp]
  (int (bal/lerp 30.0 0.0 exp)))

(defn- max-distance [exp cp ticks creative?]
  (let [max-range (lerp 25.0 60.0 exp)
        cp-limit (if creative?
                   max-range
                   (if (pos? (cp-per-block exp))
                     (/ (double cp) (cp-per-block exp))
                     max-range))]
    (min (* 2.0 (inc (long ticks)))
         (min max-range cp-limit))))

(defn- calculate-distance [x1 y1 z1 x2 y2 z2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1))
                (* (- z2 z1) (- z2 z1)))))

(defn- add-exp! [player-id amount]
  (skill-effects/add-skill-exp! player-id :mark-teleport (double amount)))

(defn- build-target-fx-payload
  [target]
  (when target
    {:target {:x (double (:target-x target))
              :y (double (:target-y target))
              :z (double (:target-z target))}
     :distance (double (:distance target))}))

(defn- destination-head-blocked?
  [player x y z]
  (when player
    (let [level (entity/player-get-level player)]
      (when level
        (let [block-pos (pos/create-block-pos (int x) (int (+ y 1.0)) (int z))
              block-state (world/world-get-block-state* level block-pos)]
          (not (world/block-state-is-air? block-state)))))))

(defn- resolve-hit-destination
  [player hit]
  (let [hit-x (double (or (:hit-x hit) (:x hit) 0.0))
        hit-y (double (or (:hit-y hit) (:y hit) 0.0))
        hit-z (double (or (:hit-z hit) (:z hit) 0.0))
        block-y (double (or (:y hit) 0.0))]
    (if (= (:hit-type hit) :entity)
      {:target-x hit-x
       :target-y (+ hit-y (double (or (:eye-height hit) 1.6)))
       :target-z hit-z}
      (let [face (:face hit)
            resolved (case face
                       :down {:target-x hit-x :target-y (- hit-y 1.0) :target-z hit-z}
                       :up {:target-x hit-x :target-y (+ hit-y 1.8) :target-z hit-z}
                       :north {:target-x hit-x :target-y (+ block-y 1.7) :target-z (- hit-z 0.6)}
                       :south {:target-x hit-x :target-y (+ block-y 1.7) :target-z (+ hit-z 0.6)}
                       :west {:target-x (- hit-x 0.6) :target-y (+ block-y 1.7) :target-z hit-z}
                       :east {:target-x (+ hit-x 0.6) :target-y (+ block-y 1.7) :target-z hit-z}
                       {:target-x hit-x :target-y hit-y :target-z hit-z})]
        (if (and (#{:north :south :west :east} face)
                 (destination-head-blocked? player
                                            (:target-x resolved)
                                            (:target-y resolved)
                                            (:target-z resolved)))
          (update resolved :target-y - 1.25)
          resolved)))))

(defn- resolve-destination
  [player-id player hold-ticks]
  (let [exp (double (or (skill-exp player-id) 0.0))
        cp (current-cp player-id)
        player-pos (when teleportation/*teleportation*
                     (teleportation/get-player-position teleportation/*teleportation* player-id))
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (when (and player-pos look-vec)
      (let [{:keys [world-id x y z]} player-pos
            creative? (boolean (and player (entity/player-creative? player)))
            dist (max-distance exp cp hold-ticks creative?)
            start-y (+ (double y) eye-height)
            hit (when raycast/*raycast*
                  (raycast/raycast-combined raycast/*raycast*
                                            world-id
                                            (double x) start-y (double z)
                                            (double (:x look-vec))
                                            (double (:y look-vec))
                                            (double (:z look-vec))
                                            dist))
            dest (if hit
                   (resolve-hit-destination player hit)
                   {:target-x (+ (double x) (* (double (:x look-vec)) dist))
                    :target-y (+ start-y (* (double (:y look-vec)) dist))
                    :target-z (+ (double z) (* (double (:z look-vec)) dist))})
            distance (calculate-distance (double x) (double y) (double z)
                                         (:target-x dest) (:target-y dest) (:target-z dest))]
        (merge dest
               {:world-id world-id
                :distance distance
                :hold-ticks (long hold-ticks)
                :exp exp
          :cp cp})))))

(defn- cached-or-resolved-target
  [player-id ctx-id player]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [hold-ticks (long (or (get-in ctx-data [:skill-state :hold-ticks]) 0))]
      (or (resolve-destination player-id player hold-ticks)
          (when (get-in ctx-data [:skill-state :has-target])
            (select-keys (:skill-state ctx-data)
                         [:world-id :target-x :target-y :target-z :distance :exp]))))))

(defn mark-teleport-fx-update-payload
  [{:keys [ctx-id]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (build-target-fx-payload (:skill-state ctx-data))))

(defn mark-teleport-fx-perform-payload
  [{:keys [ctx-id]}]
  (mark-teleport-fx-update-payload {:ctx-id ctx-id}))

(defn mark-teleport-cost-up-cp
  [{:keys [player-id ctx-id player]}]
  (if-let [target (cached-or-resolved-target player-id ctx-id player)]
    (let [distance (double (:distance target))]
      (if (>= distance minimum-valid-distance)
        (* distance (cp-per-block (double (or (:exp target) (skill-exp player-id) 0.0))))
        0.0))
    0.0))

(defn mark-teleport-cost-up-overload
  [{:keys [player-id ctx-id player]}]
  (if-let [target (cached-or-resolved-target player-id ctx-id player)]
    (let [distance (double (:distance target))]
      (if (>= distance minimum-valid-distance)
        (overload-cost (double (or (:exp target) (skill-exp player-id) 0.0)))
        0.0))
    0.0))

(defn mark-teleport-cost-creative?
  [{:keys [player]}]
  (boolean (and player (entity/player-creative? player))))

(defn mark-teleport-on-key-down
  "Initialize hold state and client marker."
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks 0 :has-target false}))

(defn mark-teleport-on-key-tick
  "Update destination marker while key is held."
  [{:keys [player-id ctx-id player]}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [next-ticks (inc (long (or (get-in ctx [:skill-state :hold-ticks]) 0)))]
      (if-let [target (resolve-destination player-id player next-ticks)]
        (ctx/update-context! ctx-id update :skill-state merge
                             (assoc target :hold-ticks next-ticks :has-target true))
        (ctx/update-context! ctx-id update :skill-state merge
                             {:hold-ticks next-ticks :has-target false})))))

(defn mark-teleport-on-key-up
  "Execute teleport when key released."
  [{:keys [player-id ctx-id player cost-ok?]}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [hold-ticks (long (or (get-in ctx [:skill-state :hold-ticks]) 0))
          target (or (resolve-destination player-id player hold-ticks)
                     (when (get-in ctx [:skill-state :has-target])
                       (select-keys (:skill-state ctx)
                                    [:world-id :target-x :target-y :target-z :distance :exp])))]
      (when (and target teleportation/*teleportation*)
        (let [distance (double (:distance target))
              exp (double (or (:exp target) (skill-exp player-id) 0.0))]
          (when (and cost-ok? (>= distance minimum-valid-distance))
            (let [success (teleportation/teleport-player! teleportation/*teleportation*
                                                          player-id
                                                          (:world-id target)
                                                          (:target-x target)
                                                          (:target-y target)
                                                          (:target-z target))]
              (when success
                (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)
                (add-exp! player-id (* 0.00018 distance))
                (skill-effects/set-main-cooldown! player-id :mark-teleport (cooldown-ticks exp))
                (log/debug "MarkTeleport: Teleported" (int distance) "blocks")))))))))

(defn mark-teleport-on-key-abort
  "Clean up teleport state on abort."
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state)
  (log/debug "MarkTeleport aborted"))

(defskill! mark-teleport
  :id :mark-teleport
  :category-id :teleporter
  :name-key "ability.skill.teleporter.mark_teleport"
  :description-key "ability.skill.teleporter.mark_teleport.desc"
  :icon "textures/abilities/teleporter/skills/mark_teleport.png"
  :level 1
  :controllable? true
  :ctrl-id :mark-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 20
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp mark-teleport-cost-up-cp
              :overload mark-teleport-cost-up-overload
              :creative? mark-teleport-cost-creative?}}
  :actions {:down! mark-teleport-on-key-down
            :tick! mark-teleport-on-key-tick
            :up! mark-teleport-on-key-up
            :abort! mark-teleport-on-key-abort}
  :fx {:start {:topic :mark-teleport/fx-start
               :payload (fn [_] {})}
       :update {:topic :mark-teleport/fx-update
                :payload mark-teleport-fx-update-payload}
       :perform {:topic :mark-teleport/fx-perform
                 :payload mark-teleport-fx-perform-payload}
       :end {:topic :mark-teleport/fx-end
             :payload (fn [_] {})}})
