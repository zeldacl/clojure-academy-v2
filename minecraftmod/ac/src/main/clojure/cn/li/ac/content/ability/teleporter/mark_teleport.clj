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
  - Experience gain: 0.00018 × distance
  - Client-side destination marker with looping teleport particles and execute sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log]))

(def ^:private minimum-valid-distance 3.0)
(def ^:private eye-height 1.6)

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :mark-teleport :exp] 0.0)))

(defn- current-cp [player-id]
  (double (or (get-in (ps/get-player-state player-id) [:resource-data :cur-cp]) 0.0)))

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- cp-per-block [exp]
  (lerp 12.0 4.0 exp))

(defn- overload-cost [exp]
  (lerp 40.0 20.0 exp))

(defn- cooldown-ticks [exp]
  (int (lerp 30.0 0.0 exp)))

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
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :mark-teleport
                                  (double amount)
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- consume-resource! [player-id player overload cp]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           (double overload)
                                           (double cp)
                                           (boolean (and player (entity/player-creative? player))))]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :mark-teleport/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id target]
  (ctx/ctx-send-to-client! ctx-id :mark-teleport/fx-update
                           {:mode :update
                            :target {:x (double (:target-x target))
                                     :y (double (:target-y target))
                                     :z (double (:target-z target))}
                            :distance (double (:distance target))}))

(defn- send-fx-end! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :mark-teleport/fx-end {:mode :end}))

(defn- send-fx-perform! [ctx-id target]
  (ctx/ctx-send-to-client! ctx-id :mark-teleport/fx-perform
                           {:mode :perform
                            :target {:x (double (:target-x target))
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
  (let [exp (double (or (get-skill-exp player-id) 0.0))
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

(defn mark-teleport-on-key-down
  "Initialize hold state and client marker."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true
                                                    :hold-ticks 0
                                                    :has-target false})
    (send-fx-start! ctx-id)
    (catch Exception e
      (log/warn "MarkTeleport key-down failed:" (ex-message e))
      (send-fx-end! ctx-id))))

(defn mark-teleport-on-key-tick
  "Update destination marker while key is held." 
  [{:keys [player-id ctx-id player]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [next-ticks (inc (long (or (get-in ctx [:skill-state :hold-ticks]) 0)))]
        (if-let [target (resolve-destination player-id player next-ticks)]
          (do
            (ctx/update-context! ctx-id update :skill-state merge
                                 (assoc target
                                        :skip-default-cooldown true
                                        :hold-ticks next-ticks
                                        :has-target true))
            (send-fx-update! ctx-id target))
          (do
            (ctx/update-context! ctx-id update :skill-state merge
                                 {:skip-default-cooldown true
                                  :hold-ticks next-ticks
                                  :has-target false})
            (send-fx-end! ctx-id)))))
    (catch Exception e
      (log/warn "MarkTeleport key-tick failed:" (ex-message e))
      (send-fx-end! ctx-id))))

(defn mark-teleport-on-key-up
  "Execute teleport when key released." 
  [{:keys [player-id ctx-id player]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [hold-ticks (long (or (get-in ctx [:skill-state :hold-ticks]) 0))
            target (or (resolve-destination player-id player hold-ticks)
                       (when (get-in ctx [:skill-state :has-target])
                         (select-keys (:skill-state ctx)
                                      [:world-id :target-x :target-y :target-z :distance :exp])))]
        (when (and target teleportation/*teleportation*)
          (let [distance (double (:distance target))
                exp (double (or (:exp target) (get-skill-exp player-id) 0.0))]
            (when (>= distance minimum-valid-distance)
              (send-fx-perform! ctx-id target)
              (when (consume-resource! player-id player (overload-cost exp) (* distance (cp-per-block exp)))
                (let [success (teleportation/teleport-player! teleportation/*teleportation*
                                                              player-id
                                                              (:world-id target)
                                                              (:target-x target)
                                                              (:target-y target)
                                                              (:target-z target))]
                  (when success
                    (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)
                    (add-exp! player-id (* 0.00018 distance))
                    (ps/update-cooldown-data! player-id cd/set-main-cooldown :mark-teleport (cooldown-ticks exp))
                    (log/debug "MarkTeleport: Teleported" (int distance) "blocks"))))))))
      (send-fx-end! ctx-id))
    (catch Exception e
      (log/warn "MarkTeleport key-up failed:" (ex-message e))
      (send-fx-end! ctx-id))))

(defn mark-teleport-on-key-abort
  "Clean up teleport state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (send-fx-end! ctx-id)
    (log/debug "MarkTeleport aborted")
    (catch Exception e
      (log/warn "MarkTeleport key-abort failed:" (ex-message e))
      (send-fx-end! ctx-id))))
