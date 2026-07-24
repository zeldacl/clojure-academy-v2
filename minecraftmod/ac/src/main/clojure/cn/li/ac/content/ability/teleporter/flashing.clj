(ns cn.li.ac.content.ability.teleporter.flashing

  "Flashing skill - toggle main state + movement sub-keys (W/A/S/D).



  Main slot key:

  - key-down toggles flashing active/inactive.



  Movement sub-key events (forward/back/left/right):

  - down/tick: update preview destination.

  - up: perform one authority teleport with resource settlement.



  No Minecraft imports."

  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]

            [cn.li.ac.ability.fx :as fx]

            [cn.li.ac.ability.service.context-dispatcher :as ctx]

            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]

                        [cn.li.ac.ability.service.skill-effects :as skill-effects]

            [cn.li.ac.ability.effects.geom :as geom]

            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]

            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]

            [cn.li.mcmod.hooks.core :as runtime-hooks]))




(def-skill-config-ops :flashing)
(def ^:private flashing-skill-id :flashing)

(def ^:private move-down-channel :flashing/move-down)

(def ^:private move-tick-channel :flashing/move-tick)

(def ^:private move-up-channel :flashing/move-up)



(def ^:private block-side-faces #{:north :south :west :east})



(defn- now-ms []

  (System/currentTimeMillis))



(defn- set-skill-state!

  [ctx-id k v]

  (ctx-skill/assoc-skill-state! ctx-id k v))



(defn- update-skill-state-root!

  [ctx-id f & args]

  (apply ctx-skill/update-skill-state-root! ctx-id f args))



(defn- movement-key->direction [movement-key]

  (case movement-key

    :forward :forward

    :back :back

    :left :left

    :right :right

    nil))



(defn- normalize-3d

  [x y z]

  (let [len (Math/sqrt (+ (* x x) (* y y) (* z z)))]

    (if (< len 1.0e-6)

      [0.0 0.0 1.0]

      [(/ x len) (/ y len) (/ z len)])))



(defn- direction-vector

  [look-vec direction]

  (let [[fx fy fz] (normalize-3d (double (:x look-vec))

                                 (double (:y look-vec))

                                 (double (:z look-vec)))]

    (case direction

      :forward [fx fy fz]

      :back [(- fx) (- fy) (- fz)]

      ;; strafe uses world-up cross for stable left/right movement vectors.

      :left [fz 0.0 (- fx)]

      :right [(- fz) 0.0 fx]

      [fx fy fz])))



(defn- destination-head-blocked?

  [world-id x y z]

  (boolean

    (helper/raycast-blocks world-id

                           (double x)

                           (+ (double y) 1.0)

                           (double z)

                           0.0

                           1.0

                           0.0

                           1.1)))



(defn- resolve-hit-destination

  [world-id hit fallback-end]

  (let [hit-x (double (or (:hit-x hit) (:x hit) (:x fallback-end) 0.0))

        hit-y (double (or (:hit-y hit) (:y hit) (:y fallback-end) 0.0))

        hit-z (double (or (:hit-z hit) (:z hit) (:z fallback-end) 0.0))

        block-y (double (or (:y hit) hit-y 0.0))]

    (if (= (:hit-type hit) :entity)

      {:to-x hit-x

       :to-y (+ hit-y (double (or (:eye-height hit) 1.6)))

       :to-z hit-z}

      (let [face (:face hit)

            resolved (case face

                       :down {:to-x hit-x :to-y (- hit-y 1.0) :to-z hit-z}

                       :up {:to-x hit-x :to-y (+ hit-y 1.8) :to-z hit-z}

                       :north {:to-x hit-x :to-y (+ block-y 1.7) :to-z (- hit-z 0.6)}

                       :south {:to-x hit-x :to-y (+ block-y 1.7) :to-z (+ hit-z 0.6)}

                       :west {:to-x (- hit-x 0.6) :to-y (+ block-y 1.7) :to-z hit-z}

                       :east {:to-x (+ hit-x 0.6) :to-y (+ block-y 1.7) :to-z hit-z}

                       {:to-x hit-x :to-y hit-y :to-z hit-z})]

        (if (and (contains? block-side-faces face)

                 (destination-head-blocked? world-id

                                            (:to-x resolved)

                                            (:to-y resolved)

                                            (:to-z resolved)))

          (update resolved :to-y - 1.25)

          resolved)))))



(defn- resolve-preview

  [player-id direction]

  (let [exp (skill-exp player-id)

        dist (cfg-lerp :movement.blink-distance exp)

        pos (helper/player-position player-id)

        look (helper/player-look-vec player-id)]

    (when (and pos look)

      (let [world-id (:world-id pos)

            start-x (double (:x pos))

            start-y (double (:y pos))

            start-z (double (:z pos))

            [dx dy dz] (direction-vector look direction)

            end-x (+ start-x (* (double dist) dx))

            end-y (+ start-y (* (double dist) dy))

            end-z (+ start-z (* (double dist) dz))

            hit (helper/raycast-combined world-id

                                         start-x

                                         start-y

                                         start-z

                                         dx

                                         dy

                                         dz

                                         (double dist))

            resolved (if hit

                       (resolve-hit-destination world-id hit {:x end-x :y end-y :z end-z})

                       {:to-x end-x :to-y end-y :to-z end-z})]

        {:direction direction

         :distance (double dist)

         :world-id world-id

         :from-x start-x

         :from-y start-y

         :from-z start-z

         :to-x (double (:to-x resolved))

         :to-y (double (:to-y resolved))

         :to-z (double (:to-z resolved))}))))



(defn- clear-preview!

  [ctx-id]

  (update-skill-state-root! ctx-id

                            (fn [st]

                              (-> (or st {})

                                  (dissoc :direction)

                                  (dissoc :preview)))))



(defn- maintain-active-state!

  [ctx-id ctx-data]

  (let [now (now-ms)

        player-id (:player-uuid ctx-data)

        expires-at (long (or (get-in ctx-data [:skill-state :expires-at-ms]) 0))

        overload-floor (double (or (get-in ctx-data [:skill-state :overload-floor]) 0.0))

        fall-protect-until (long (or (get-in ctx-data [:skill-state :fall-protect-until-ms]) 0))]

    (cond

      (not (get-in ctx-data [:skill-state :active?]))

      nil



      (and (pos? expires-at) (> now expires-at))

      (do

        (ctx/terminate-context! ctx-id nil)

        nil)



      :else

      (do

        (skill-effects/enforce-overload-floor! player-id overload-floor)

        (when (> fall-protect-until now)

          (helper/reset-fall-damage! player-id))

        ctx-data))))



(defn- perform-flash!

  [player-id ctx-id direction]

  (let [preview (resolve-preview player-id direction)

        exp (skill-exp player-id)

        cp-cost (cfg-lerp :cost.blink.cp exp)

        overload-cost (cfg-lerp :cost.blink.overload exp)]

    (when (and preview

               (>= (skill-effects/current-cp player-id) (double cp-cost)))

      (let [world-id (geom/world-id-of player-id)]

        (when (helper/teleport-to! player-id world-id

                                   (:to-x preview)

                                   (:to-y preview)

                                   (:to-z preview))

          (skill-effects/perform-resource! player-id (double overload-cost) (double cp-cost))

          (let [protect-ticks (cfg-lerp-int :timing.post-blink-fall-protect-ticks exp)

                protect-until (+ (now-ms) (* 50 (long (max 0 protect-ticks))))]

          (set-skill-state! ctx-id [:fall-protect-until-ms] protect-until)

            (helper/reset-fall-damage! player-id))

          (skill-effects/add-skill-exp! player-id flashing-skill-id

                                        (cfg-double :progression.exp-blink))

          (ach-dispatcher/trigger-custom-event! player-id "teleporter.flashing")

          ;; Original's serverPerform sendToClient(MSG_PERFORM) plays the
          ;; blink sound for owner + nearby unconditionally (only the
          ;; anti-fall-damage GravityCancellor hack is isLocal-gated).
          (fx/send-local-and-nearby! ctx-id {:topic :flashing/fx-perform :mode :perform} nil preview)

          true)))))



(defn- update-preview!

  [ctx-id player-id direction mode]

  (when-let [preview (resolve-preview player-id direction)]

    (update-skill-state-root! ctx-id

                              (fn [st]

                                (-> (or st {})

                                    (assoc :active? true)

                                    (assoc :direction direction)

                                    (assoc :preview preview))))

    (fx/send! ctx-id {:topic mode} nil preview)))



(defn- register-movement-listeners!

  [ctx-id]

  (when-not (get-in (ctx-skill/get-context ctx-id) [:skill-state :listeners-installed?])

    (ctx/ctx-on! ctx-id move-down-channel

                 (fn [{:keys [key]}]

                   (when-let [ctx-data (ctx-skill/get-context ctx-id)]

                     (when-let [active-ctx (maintain-active-state! ctx-id ctx-data)]

                       (when-let [direction (movement-key->direction key)]

                         (update-preview! ctx-id (:player-uuid active-ctx) direction :flashing/fx-preview-start))))))

    (ctx/ctx-on! ctx-id move-tick-channel

                 (fn [{:keys [key]}]

                   (when-let [ctx-data (ctx-skill/get-context ctx-id)]

                     (when-let [active-ctx (maintain-active-state! ctx-id ctx-data)]

                       (when-let [direction (movement-key->direction key)]

                         (update-preview! ctx-id (:player-uuid active-ctx) direction :flashing/fx-preview-update))))))

    (ctx/ctx-on! ctx-id move-up-channel

                 (fn [{:keys [key]}]

                   (when-let [ctx-data (ctx-skill/get-context ctx-id)]

                     (when-let [active-ctx (maintain-active-state! ctx-id ctx-data)]

                       (when-let [direction (movement-key->direction key)]

                         (perform-flash! (:player-uuid active-ctx) ctx-id direction)

                         (clear-preview! ctx-id)

                         (fx/send! ctx-id {:topic :flashing/fx-preview-end} nil {:direction direction}))))))

              (set-skill-state! ctx-id [:listeners-installed?] true)))



(defn flashing-activate!

  [ctx-id player-id _skill-id _exp cost-ok? _hold-ticks _cost-stage _player-ref]

  (when cost-ok?

    (let [exp (skill-exp player-id)

          max-active-ticks (cfg-lerp-int :timing.max-active-ticks exp)

          overload-floor (double (or (skill-effects/player-path player-id [:resource-data :cur-overload] 0.0) 0.0))

          now (now-ms)]

      (update-skill-state-root! ctx-id

                                (fn [st]

                                  (-> (or st {})

                                      (assoc :active? true)

                                      (assoc :active-exp exp)

                                      (assoc :activated-at-ms now)

                                      (assoc :expires-at-ms (+ now (* 50 (long (max 0 max-active-ticks)))))

                                      (assoc :overload-floor overload-floor)

                                      (dissoc :direction)

                                      (dissoc :preview)))))

    (register-movement-listeners! ctx-id)))



(defn flashing-tick!

  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]

  (when-let [ctx-data (ctx-skill/get-context ctx-id)]

    (maintain-active-state! ctx-id ctx-data)))



(defn flashing-deactivate!

  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]

  (fx/send! ctx-id {:topic :flashing/fx-state-end} nil {})

  (update-skill-state-root! ctx-id

                            (fn [st]

                              (-> (or st {})

                                  (assoc :active? false)

                                  (dissoc :direction)

                                  (dissoc :preview))))

  (skill-effects/set-main-cooldown! player-id flashing-skill-id

                                    (cfg-lerp-int :cooldown.deactivate-ticks (skill-exp player-id))))



(defn flashing-abort!

  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]

  (fx/send! ctx-id {:topic :flashing/fx-state-end} nil {})

  (update-skill-state-root! ctx-id

                            (fn [st]

                              (-> (or st {})

                                  (assoc :active? false)

                                  (dissoc :direction)

                                  (dissoc :preview))))

  (skill-effects/set-main-cooldown! player-id flashing-skill-id

                                    (cfg-lerp-int :cooldown.deactivate-ticks (skill-exp player-id))))



(defskill flashing-skill

  :id              :flashing

  :category-id     :teleporter

  :name-key        "ability.skill.teleporter.flashing"

  :description-key "ability.skill.teleporter.flashing.desc"

  :icon            "textures/abilities/teleporter/skills/flashing.png"

  :ui-position     [220 20]



  :ctrl-id         :flashing

  :cp-consume-speed 0.0

  :overload-consume-speed 0.0

  :pattern         :toggle

  :input-policy    {:terminate-on-key-up? false}

  :cost            {:down {:overload (fn [player-id _skill-id _exp]

                                       (cfg-lerp :cost.down.overload

                                                        (skill-exp player-id)))

                           :cp (fn [player-id _skill-id _exp]

                                 (cfg-lerp :cost.down.cp

                                                  (skill-exp player-id)))}}

  :cooldown        {:mode :manual}

  :actions         {:activate! flashing-activate!

                    :tick! flashing-tick!

                    :deactivate! flashing-deactivate!

                    :abort! flashing-abort!}

  :fx              {:start {:topic :flashing/fx-state-start :payload (fn [_] {})}

                    :end   {:topic :flashing/fx-state-end :payload (fn [_] {})}}

  :prerequisites   [{:skill-id :shift-teleport :min-exp 0.8}])



