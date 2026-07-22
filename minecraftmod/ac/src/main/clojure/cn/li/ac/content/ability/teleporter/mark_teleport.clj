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

  - Experience gain: 0.00018 * distance

  - Client-side destination marker with looping teleport particles and execute sound



  No Minecraft imports."

  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]

            [cn.li.ac.ability.fx :as fx]

            [cn.li.ac.ability.service.context-dispatcher :as ctx]

            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]

                        [cn.li.ac.ability.service.skill-effects :as skill-effects]

            [cn.li.ac.util.math.vec3 :as vec3]

                        [cn.li.mcmod.platform.entity :as entity]

            [cn.li.mcmod.platform.position :as pos]

            [cn.li.mcmod.platform.raycast :as raycast]

            [cn.li.ac.ability.effects.motion :as motion-effects]

            [cn.li.mcmod.platform.world :as world]

            [cn.li.mcmod.util.log :as log]

            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.content.ability.teleporter.release-cast-base :as release-cast]))



(def-skill-config-ops :mark-teleport)

(def ^:private mark-teleport-skill-id :mark-teleport)



(defn- current-cp [player-id]

  (skill-effects/current-cp player-id)

  )



(defn- cp-per-block [exp]

  (cfg-lerp :cost.up.cp-per-block exp))



(defn- overload-cost [exp]

  (cfg-lerp :cost.up.overload exp))



(defn- cooldown-ticks [exp]

  (cfg-lerp-int :cooldown.ticks exp))



(defn- max-distance [exp cp ticks creative?]

  (let [max-range (cfg-lerp :targeting.range exp)

        cp-limit (if creative?

                   max-range

                   (if (pos? (cp-per-block exp))

                     (/ (double cp) (cp-per-block exp))

                     max-range))]

    (min (* (cfg-double :targeting.range-per-hold-tick)

            (inc (long ticks)))

         (min max-range cp-limit))))



(defn- add-exp! [player-id amount]

  (skill-effects/add-skill-exp! player-id mark-teleport-skill-id (double amount)))



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

          (not (world/block-state-is-air block-state)))))))



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

        player-pos (when (motion-effects/teleportation-available?)

                     (motion-effects/player-position player-id))

        look-vec (when (raycast/available?)

                   (raycast/get-player-look-vector* player-id))]

    (when (and player-pos look-vec)

      (let [{:keys [world-id x y z]} player-pos

            creative? (boolean (and player (entity/player-creative? player)))

            dist (max-distance exp cp hold-ticks creative?)

            start-y (+ (double y) (cfg-double :targeting.eye-height))

            hit (when (raycast/available?)

                  (raycast/raycast-combined*

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

            distance (vec3/euclidean-distance (double x) (double y) (double z)

                                              (:target-x dest) (:target-y dest) (:target-z dest))]

        (merge dest

               {:world-id world-id

                :distance distance

                :hold-ticks (long hold-ticks)

                :exp exp

          :cp cp})))))



(defn- cached-or-resolved-target

  [player-id ctx-id player]

  (when-let [ctx-data (ctx-skill/get-context ctx-id)]

    (let [hold-ticks (long (or (get-in ctx-data [:skill-state :hold-ticks]) 0))]

      (or (resolve-destination player-id player hold-ticks)

          (when (get-in ctx-data [:skill-state :has-target])

            (select-keys (:skill-state ctx-data)

                         [:world-id :target-x :target-y :target-z :distance :exp]))))))



(defn mark-teleport-fx-update-payload

  [{:keys [ctx-id]}]

  (when-let [ctx-data (ctx-skill/get-context ctx-id)]

    (build-target-fx-payload (:skill-state ctx-data))))



(defn mark-teleport-fx-perform-payload

  [{:keys [ctx-id]}]

  (mark-teleport-fx-update-payload {:ctx-id ctx-id}))



(defn- active-ctx-id [player-id skill-id]
  (some->> (ctx/active-contexts player-id)
           (filter #(= skill-id (:skill-id %)))
           first
           :id))

(defn mark-teleport-cost-up-cp

  [player-id skill-id _exp]

  (if-let [target (when-let [ctx-id (active-ctx-id player-id skill-id)]
                     (cached-or-resolved-target player-id ctx-id nil))]

    (let [distance (double (:distance target))]

      (if (>= distance (cfg-double :targeting.min-distance))

        (* distance (cp-per-block (double (or (:exp target) (skill-exp player-id) 0.0))))

        0.0))

    0.0))



(defn mark-teleport-cost-up-overload

  [player-id skill-id _exp]

  (if-let [target (when-let [ctx-id (active-ctx-id player-id skill-id)]
                     (cached-or-resolved-target player-id ctx-id nil))]

    (let [distance (double (:distance target))]

      (if (>= distance (cfg-double :targeting.min-distance))

        (overload-cost (double (or (:exp target) (skill-exp player-id) 0.0)))

        0.0))

    0.0))



(defn mark-teleport-cost-creative?

  [_player-id _skill-id _exp]

  false)





(defn- mark-teleport-on-key-tick-impl!

  "Update destination marker while key is held."

  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage player-ref]

  (when-let [ctx (ctx-skill/get-context ctx-id)]

    (let [next-ticks (inc (long (or (get-in ctx [:skill-state :hold-ticks]) 0)))]

      (if-let [target (resolve-destination player-id player-ref next-ticks)]

        (ctx-skill/replace-skill-state! ctx-id

                               (merge (:skill-state ctx)

                                      (assoc target :hold-ticks next-ticks :has-target true)))

        (ctx-skill/replace-skill-state! ctx-id

                               (merge (:skill-state ctx)

                                      {:hold-ticks next-ticks :has-target false}))))))



(defn- mark-teleport-on-key-up-impl!

  "Execute teleport when key released."

  [ctx-id player-id _skill-id _exp cost-ok? _hold-ticks _cost-stage player-ref]

  (when-let [ctx (ctx-skill/get-context ctx-id)]

    (let [hold-ticks (long (or (get-in ctx [:skill-state :hold-ticks]) 0))

          target (or (resolve-destination player-id player-ref hold-ticks)

                     (when (get-in ctx [:skill-state :has-target])

                       (select-keys (:skill-state ctx)

                                    [:world-id :target-x :target-y :target-z :distance :exp])))]

      (if target

        (ctx-skill/replace-skill-state! ctx-id

                               (merge (:skill-state ctx)

                                      (assoc target :hold-ticks hold-ticks :has-target true)))

        (ctx-skill/replace-skill-state! ctx-id {:hold-ticks hold-ticks :has-target false}))

      (when (and target (motion-effects/teleportation-available?))

        (let [distance (double (:distance target))

              exp (double (or (:exp target) (skill-exp player-id) 0.0))]

          (when (and cost-ok? (>= distance (cfg-double :targeting.min-distance)))

            (let [success (motion-effects/teleport-player! player-id

                                                          (:world-id target)

                                                          (:target-x target)

                                                          (:target-y target)

                                                          (:target-z target))]

              (when success

                (fx/send! ctx-id {:topic :mark-teleport/fx-perform :mode :perform} nil

                          (merge {:skill-id mark-teleport-skill-id

                                  :player-id player-id

                                  :ctx-id ctx-id}

                                 (or (build-target-fx-payload target) {})))

                (motion-effects/reset-fall-damage! player-id)

                (add-exp! player-id (* (cfg-double :progression.exp-per-distance)

                                       distance))

                (skill-effects/set-main-cooldown! player-id mark-teleport-skill-id (cooldown-ticks exp))

                (log/debug "MarkTeleport: Teleported" (int distance) "blocks")))))))))






(def ^:private release-cast-ops
  (release-cast/build-ops
    {:initial-state {:hold-ticks 0 :has-target false}
     :require-cost-on-down? false
     :abort-log-label "MarkTeleport aborted"
     :tick! mark-teleport-on-key-tick-impl!
     :up! mark-teleport-on-key-up-impl!}))

(defn mark-teleport-on-key-down [& args] (apply release-cast/down! release-cast-ops args))
(defn mark-teleport-on-key-tick [& args] (apply release-cast/tick! release-cast-ops args))
(defn mark-teleport-on-key-up [& args] (apply release-cast/up! release-cast-ops args))
(defn mark-teleport-on-key-abort [& args] (apply release-cast/abort! release-cast-ops args))

(defskill mark-teleport-skill

  :id :mark-teleport

  :category-id :teleporter

  :name-key "ability.skill.teleporter.mark_teleport"

  :description-key "ability.skill.teleporter.mark_teleport.desc"

  :icon "textures/abilities/teleporter/skills/mark_teleport.png"

  :ui-position [70 16]



  :ctrl-id :mark-teleport

  :cp-consume-speed 0.0

  :overload-consume-speed 0.0

  :cooldown-ticks (fn [player-id _skill-id _exp]

                    (cooldown-ticks (skill-exp player-id)))

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

       :end {:topic :mark-teleport/fx-end

             :payload (fn [_] {})}}

  :prerequisites [{:skill-id :threatening-teleport :min-exp 0.4}])
