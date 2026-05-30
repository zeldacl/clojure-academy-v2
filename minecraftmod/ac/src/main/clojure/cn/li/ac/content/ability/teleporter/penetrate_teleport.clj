(ns cn.li.ac.content.ability.teleporter.penetrate-teleport
  "PenetrateTP skill - preview and teleport through an obstacle.

  Pattern: :release-cast
  Server behavior:
  - Resolve destination from player look vector and requested distance
  - Clamp requested distance by max range and available CP
  - Recompute final destination on key-up server-side only

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants and helpers
;; ---------------------------------------------------------------------------

(def ^:private penetrate-teleport-skill-id :penetrate-teleport)
(def ^:private min-distance 0.5)
(def ^:private stage2-clearance-steps 4)
(def ^:private distance-channel :penetrate-tp/set-distance)

(defn- floor-int [x]
  (int (Math/floor (double x))))

(defn- clamp [v lo hi]
  (max (double lo) (min (double hi) (double v))))

(defn- resolve-exp [player-id]
  (helper/skill-exp player-id penetrate-teleport-skill-id))

(defn- cp-per-block [exp]
  (helper/cfg-lerp penetrate-teleport-skill-id :cost.up.cp-per-block exp))

(defn- max-distance [exp]
  (helper/cfg-lerp penetrate-teleport-skill-id :targeting.max-distance exp))

(defn- overload-cost [exp]
  (helper/cfg-lerp penetrate-teleport-skill-id :cost.up.overload exp))

(defn- cooldown-ticks [exp]
  (helper/cfg-lerp-int penetrate-teleport-skill-id :cooldown.ticks exp))

(defn- exp-per-distance []
  (helper/cfg-double penetrate-teleport-skill-id :progression.exp-per-distance))

(defn- scan-step []
  (helper/cfg-double penetrate-teleport-skill-id :targeting.scan-step))

(defn- solid? [world-id bx by bz]
  (when bm/*block-manipulation*
    (let [h (bm/get-block-hardness bm/*block-manipulation* world-id bx by bz)]
      (and (some? h) (pos? (double h))))))

(defn- has-place?
  "Original AC semantics: two non-solid blocks for feet/head."
  [world-id x y z]
  (let [ix (floor-int x)
        iy (floor-int y)
        iz (floor-int z)]
    (and (not (solid? world-id ix iy iz))
         (not (solid? world-id ix (inc iy) iz)))))

(defn- clamp-distance-by-cp
  [desired-distance current-cp exp]
  (let [per-block (max 1.0e-6 (cp-per-block exp))
        cp-limit (/ (double current-cp) per-block)
        max-dist (max-distance exp)]
    (clamp desired-distance min-distance (min max-dist cp-limit))))

(defn- resolve-destination
  [player-id look-vec distance]
  (when (and look-vec bm/*block-manipulation*)
    (let [world-id (geom/world-id-of player-id)
          player-pos (helper/player-position player-id)]
      (when (and world-id player-pos)
        (let [step (scan-step)
              max-steps (max 1 (int (Math/ceil (/ (double distance) step))))
              px (double (:x player-pos))
              py (double (:y player-pos))
              pz (double (:z player-pos))
              dx (double (:x look-vec))
              dy (double (:y look-vec))
              dz (double (:z look-vec))]
          (loop [i 0
                 stage 0
                 clear-steps 0
                 x px
                 y py
                 z pz
                 traveled 0.0]
            (if (> i max-steps)
              {:x x :y y :z z :distance traveled :available? (not= stage 1)}
              (let [place? (has-place? world-id x y z)]
                (cond
                  (and (= stage 0) (not place?))
                  (recur (inc i) 1 clear-steps
                         (+ x (* step dx))
                         (+ y (* step dy))
                         (+ z (* step dz))
                         (+ traveled step))

                  (and (= stage 1) place?)
                  (recur (inc i) 2 0
                         (+ x (* step dx))
                         (+ y (* step dy))
                         (+ z (* step dz))
                         (+ traveled step))

                  (= stage 2)
                  (if (or (not place?) (> clear-steps stage2-clearance-steps))
                    {:x x :y y :z z :distance traveled :available? true}
                    (recur (inc i) 2 (inc clear-steps)
                           (+ x (* step dx))
                           (+ y (* step dy))
                           (+ z (* step dz))
                           (+ traveled step)))

                  :else
                  (recur (inc i) stage clear-steps
                         (+ x (* step dx))
                         (+ y (* step dy))
                         (+ z (* step dz))
                         (+ traveled step)))))))))))

(defn- resolve-preview
  [player-id desired-distance]
  (let [exp (resolve-exp player-id)
        cp (skill-effects/current-cp player-id)
        distance (clamp-distance-by-cp desired-distance cp exp)
        look-vec (helper/player-look-vec player-id)
        dest (resolve-destination player-id look-vec distance)]
    {:exp exp
     :desired-distance desired-distance
     :distance distance
     :cp-per-block (cp-per-block exp)
     :dest dest
     :available? (boolean (:available? dest))}))

(defn- default-desired-distance
  [player-id]
  (max-distance (resolve-exp player-id)))

(defn- update-distance
  [player-id distance-state payload]
  (let [exp (resolve-exp player-id)
        lo min-distance
        hi (max-distance exp)
        current (double (or (:desired-distance distance-state) hi))
        next-distance (cond
                        (number? (:distance payload))
                        (clamp (double (:distance payload)) lo hi)

                        (number? (:delta payload))
                        (clamp (+ current (double (:delta payload))) lo hi)

                        :else
                        current)]
    (assoc distance-state :desired-distance next-distance)))

(defn- preview-payload
  [ctx-id]
  (let [ctx-data (ctx/get-context ctx-id)
        preview (get-in ctx-data [:skill-state :preview])
        dest (:dest preview)]
    {:distance (double (or (:distance preview) 0.0))
     :available? (boolean (:available? preview))
     :x (:x dest)
     :y (:y dest)
     :z (:z dest)}))

(defn- ensure-up-resolve!
  [ctx-id player-id]
  (let [ctx-data (ctx/get-context ctx-id)
        existing (get-in ctx-data [:skill-state :up-resolve])
        desired-distance (double (or (get-in ctx-data [:skill-state :desired-distance])
                                     (default-desired-distance player-id)))]
    (if (and existing
             (= desired-distance (double (or (:desired-distance existing) 0.0))))
      existing
      (let [preview (resolve-preview player-id desired-distance)]
        (ctx/update-context! ctx-id assoc-in [:skill-state :up-resolve] preview)
        preview))))

(defn- up-cost-cp
  [{:keys [ctx-id player-id]}]
  (let [resolved (ensure-up-resolve! ctx-id player-id)]
    (* (double (:distance resolved)) (double (:cp-per-block resolved)))))

(defn- up-cost-overload
  [{:keys [ctx-id player-id]}]
  (double (overload-cost (:exp (ensure-up-resolve! ctx-id player-id)))))

(defn- up-cost-creative?
  [{:keys [player]}]
  (boolean (and player false)))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn penetrate-tp-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [desired (default-desired-distance player-id)]
      (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks 0
                                                      :desired-distance desired
                                                      :preview (resolve-preview player-id desired)
                                                      :up-resolve nil})
      (ctx/ctx-on! ctx-id distance-channel
                   (fn [payload]
                     (ctx/update-context! ctx-id update :skill-state
                                          (fn [st]
                                            (let [base (or st {:desired-distance desired})
                                                  updated (update-distance player-id base payload)
                                                  resolved (resolve-preview player-id (:desired-distance updated))]
                                              (-> updated
                                                  (assoc :preview resolved)
                                                  (assoc :up-resolve nil))))))))))

(defn penetrate-tp-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ctx-data (ctx/get-context ctx-id)
        desired (double (or (get-in ctx-data [:skill-state :desired-distance])
                            (default-desired-distance player-id)))
        preview (resolve-preview player-id desired)]
    (ctx/update-context! ctx-id update :skill-state
                         (fn [st]
                           (-> (or st {})
                               (assoc :hold-ticks (long hold-ticks))
                               (assoc :desired-distance desired)
                               (assoc :preview preview)
                               (assoc :up-resolve nil))))))

(defn penetrate-tp-up!
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (let [resolved (ensure-up-resolve! ctx-id player-id)
          dest (:dest resolved)
          exp (:exp resolved)
          success? (and cost-ok?
                        (:available? resolved)
                        dest
                        (helper/teleport-to! player-id
                                             (geom/world-id-of player-id)
                                             (:x dest) (:y dest) (:z dest)))]
      (if success?
        (do
          (skill-effects/add-skill-exp! player-id penetrate-teleport-skill-id
                                        (* (exp-per-distance)
                                           (double (:distance resolved))))
          (ach-dispatcher/trigger-custom-event! player-id "teleporter.ignore_barrier")
          (skill-effects/set-main-cooldown! player-id penetrate-teleport-skill-id (cooldown-ticks exp))
          (ctx/ctx-send-to-client! ctx-id :penetrate-tp/fx-perform dest))
        (log/debug "PenetrateTP: execute failed" {:cost-ok? cost-ok?
                                                   :available? (:available? resolved)})))
    (catch Exception e
      (log/warn "PenetrateTP up! failed:" (ex-message e)))))

(defn penetrate-tp-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill penetrate-teleport-skill
  :id             :penetrate-teleport
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.penetrate_teleport"
  :description-key "ability.skill.teleporter.penetrate_teleport.desc"
  :icon           "textures/abilities/teleporter/skills/penetrate_teleport.png"
  :ui-position    [60 160]
  :level          2
  :controllable?  true
  :ctrl-id        :penetrate-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:up {:cp up-cost-cp
                        :overload up-cost-overload
                        :creative? up-cost-creative?}}
  :cooldown       {:mode :manual}
  :actions        {:down!  penetrate-tp-down!
                   :tick!  penetrate-tp-tick!
                   :up!    penetrate-tp-up!
                   :abort! penetrate-tp-abort!}
  :fx             {:start {:topic :penetrate-tp/fx-start :payload (fn [_] {})}
                   :update {:topic :penetrate-tp/fx-update :payload (fn [{:keys [ctx-id]}]
                                                                      (preview-payload ctx-id))}
                   :end   {:topic :penetrate-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :threatening-teleport :min-exp 0.5}])
