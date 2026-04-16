(ns cn.li.ac.content.ability.vecmanip.blood-retrograde
  "BloodRetrograde skill port aligned to original AcademyCraft Blood Retrograde.

  Key alignment points:
  - Hold to charge up to 30 ticks; auto-release at max charge
  - Only living-entity hits within 2 blocks can perform the skill
  - Local walk speed slows from 0.1 to 0.007 over the first 20 ticks
  - Release consumes overload lerp(55,40) and CP lerp(280,350)
  - Damage lerp(30,60), cooldown lerp(90,40), exp gain 0.002
  - Successful hit plays blood splash / blood spray visuals and sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def ^:private max-charge-ticks 30)
(def ^:private target-distance 2.0)
(def ^:private spray-angles [0.0 30.0 45.0 60.0 80.0 -30.0 -45.0 -60.0 -80.0])

(declare get-skill-exp)

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- v+ [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn- v- [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v* [a scalar]
  {:x (* (double (:x a)) (double scalar))
   :y (* (double (:y a)) (double scalar))
   :z (* (double (:z a)) (double scalar))})

(defn- dot [a b]
  (+ (* (:x a) (:x b))
     (* (:y a) (:y b))
     (* (:z a) (:z b))))

(defn- vlen [a]
  (Math/sqrt (dot a a)))

(defn- normalize [a]
  (let [len (max 1.0e-6 (vlen a))]
    (v* a (/ 1.0 len))))

(defn- cross [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- rotate-around-axis [vec axis degrees]
  (let [axis-unit (normalize axis)
        theta (Math/toRadians (double degrees))
        cos-theta (Math/cos theta)
        sin-theta (Math/sin theta)
        term1 (v* vec cos-theta)
        term2 (v* (cross axis-unit vec) sin-theta)
        term3 (v* axis-unit (* (dot axis-unit vec) (- 1.0 cos-theta)))]
    (normalize (v+ (v+ term1 term2) term3))))

(defn- world-up []
  {:x 0.0 :y 1.0 :z 0.0})

(defn- current-eye-pos [player-id]
  (let [player-pos (get (ps/get-player-state player-id) :position {:x 0.0 :y 64.0 :z 0.0})]
    {:x (double (:x player-pos))
     :y (+ (double (:y player-pos)) 1.62)
     :z (double (:z player-pos))}))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- get-player-look [player-id]
  (or (when raycast/*raycast*
        (some-> (raycast/get-player-look-vector raycast/*raycast* player-id)
                normalize))
      {:x 0.0 :y 0.0 :z 1.0}))

(defn- get-target-info [world-id target-id fallback-hit]
  (let [fallback {:uuid target-id
                  :x (double (or (:x fallback-hit) 0.0))
                  :y (double (or (:y fallback-hit) 0.0))
                  :z (double (or (:z fallback-hit) 0.0))
                  :width 0.6
                  :height 1.8
                  :eye-height 1.62}]
    (or (when (and world-effects/*world-effects* target-id)
          (some (fn [{:keys [uuid] :as entity}]
                  (when (= uuid target-id)
                    (merge fallback entity)))
                (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                       world-id
                                                       (:x fallback)
                                                       (:y fallback)
                                                       (:z fallback)
                                                       4.0)))
        fallback)))

(defn- overload-cost [exp]
  (lerp 55.0 40.0 exp))

(defn- cp-cost [exp]
  (lerp 280.0 350.0 exp))

(defn- damage-value [exp]
  (lerp 30.0 60.0 exp))

(defn- cooldown-ticks [exp]
  (int (lerp 90.0 40.0 exp)))

(defn- add-exp! [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :blood-retrograde
                                  (double amount)
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- try-consume-resource! [player-id overload cp]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           (double overload)
                                           (double cp)
                                           false)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :blood-retrograde/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id ticks]
  (ctx/ctx-send-to-client! ctx-id :blood-retrograde/fx-update
                           {:mode :update
                            :ticks (long ticks)
                            :charge-ratio (clamp01 (/ (double ticks) 20.0))}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :blood-retrograde/fx-end
                           {:mode :end
                            :performed? (boolean performed?)}))

(defn- spray-hit-payloads [player-id world-id target-info]
  (let [base-look (get-player-look player-id)
        head-pos {:x (:x target-info)
                  :y (+ (double (:y target-info)) (* (double (:height target-info)) 0.6))
                  :z (:z target-info)}]
    (->> spray-angles
         (mapcat (fn [pitch-deg]
                   (let [yaw-jitter (- (* (rand) 40.0) 20.0)
                         yaw-turned (rotate-around-axis base-look (world-up) yaw-jitter)
                         right-axis (let [raw (cross yaw-turned (world-up))]
                                      (if (> (vlen raw) 1.0e-5)
                                        (normalize raw)
                                        {:x 1.0 :y 0.0 :z 0.0}))
                         dir (rotate-around-axis yaw-turned right-axis pitch-deg)
                         start (v- head-pos (v* dir 0.5))
                         hit (when raycast/*raycast*
                               (raycast/raycast-blocks raycast/*raycast*
                                                      world-id
                                                      (:x start) (:y start) (:z start)
                                                      (:x dir) (:y dir) (:z dir)
                                                      5.5))]
                     (when (map? hit)
                       (repeatedly (+ 2 (rand-int 2))
                                   (fn []
                                     {:x (double (or (:x hit) 0.0))
                                      :y (double (or (:y hit) 0.0))
                                      :z (double (or (:z hit) 0.0))
                                      :face (:face hit)
                                      :size (* (+ 1.1 (* (rand) 0.3))
                                               (if (contains? #{:up :down} (:face hit)) 1.0 0.8))
                                      :rotation (* (rand) 360.0)
                                      :offset-u (- (* (rand) 0.3) 0.15)
                                      :offset-v (- (* (rand) 0.3) 0.15)
                                      :texture-id (rand-int 3)}))))))
         (filter some?)
         vec)))

(defn- splash-payloads [look-dir target-info]
  (let [width (double (or (:width target-info) 0.6))
        height (double (or (:height target-info) 1.8))]
    (vec
      (repeatedly (+ 6 (rand-int 4))
                  (fn []
                    (let [dv {:x (* (- (* (rand) 2.0) 1.0) width)
                              :y (* (rand) height)
                              :z (* (- (* (rand) 2.0) 1.0) width)}
                          pos (v+ (v+ {:x (:x target-info)
                                       :y (:y target-info)
                                       :z (:z target-info)}
                                     dv)
                                  (v* look-dir 0.2))]
                      {:x (:x pos)
                       :y (:y pos)
                       :z (:z pos)
                       :size (+ 1.4 (* (rand) 0.4))}))))))

(defn- send-fx-perform! [ctx-id player-id world-id target-id hit]
  (let [target-info (get-target-info world-id target-id hit)
        look-dir (get-player-look player-id)]
    (ctx/ctx-send-to-client! ctx-id :blood-retrograde/fx-perform
                             {:mode :perform
                              :sound-pos {:x (:x target-info)
                                          :y (+ (double (:y target-info)) (* (double (:height target-info)) 0.5))
                                          :z (:z target-info)}
                              :splashes (splash-payloads look-dir target-info)
                              :sprays (spray-hit-payloads player-id world-id target-info)})))

(defn- finish! [ctx-id performed?]
  (ctx/update-context! ctx-id update :skill-state
                       (fn [skill-state]
                         (assoc (or skill-state {})
                                :executed? true
                                :ended? true
                                :performed? (boolean performed?)
                                :skip-default-cooldown true)))
  (send-fx-end! ctx-id performed?)
  (ctx/terminate-context! ctx-id nil))

(defn- try-perform! [player-id ctx-id hit]
  (let [target-id (:entity-id hit)
        world-id (player-world-id player-id)
        exp (get-skill-exp player-id)]
    (when target-id
      (if-not (try-consume-resource! player-id (overload-cost exp) (cp-cost exp))
        false
        (do
          (ps/update-cooldown-data! player-id cd/set-main-cooldown :blood-retrograde (max 1 (cooldown-ticks exp)))
          (when entity-damage/*entity-damage*
            (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                world-id
                                                target-id
                                                (damage-value exp)
                                                :generic))
          (add-exp! player-id 0.002)
          (send-fx-perform! ctx-id player-id world-id target-id hit)
          true)))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :blood-retrograde :exp] 0.0)))

(defn blood-retrograde-on-key-down
  "Initialize charge state and local charge slowdown."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:ticks 0
                          :executed? false
                          :ended? false
                          :skip-default-cooldown true})
    (send-fx-start! ctx-id)
    (send-fx-update! ctx-id 0)
    (log/debug "BloodRetrograde charge started")
    (catch Exception e
      (log/warn "BloodRetrograde key-down failed:" (ex-message e)))))

(defn blood-retrograde-on-key-tick
  "Update charge progress and auto-release at max charge."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            executed? (boolean (:executed? skill-state false))]
        (when-not executed?
          (let [ticks (inc (long (or (:ticks skill-state) 0)))]
            (ctx/update-context! ctx-id update :skill-state assoc
                                 :ticks ticks
                                 :skip-default-cooldown true)
            (send-fx-update! ctx-id ticks)
            (when (>= ticks max-charge-ticks)
              (let [hit (when raycast/*raycast*
                          (raycast/raycast-from-player raycast/*raycast* player-id target-distance true))
                    performed? (boolean (and hit (try-perform! player-id ctx-id hit)))]
                (finish! ctx-id performed?)
                (log/debug "BloodRetrograde auto-release" "ticks" ticks "performed" performed?)))))))
    (catch Exception e
      (log/warn "BloodRetrograde key-tick failed:" (ex-message e)))))

(defn blood-retrograde-on-key-up
  "Execute the skill on release if a valid target is found."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            executed? (boolean (:executed? skill-state false))]
        (when-not executed?
          (let [hit (when raycast/*raycast*
                      (raycast/raycast-from-player raycast/*raycast* player-id target-distance true))
                performed? (boolean (and hit (try-perform! player-id ctx-id hit)))]
            (finish! ctx-id performed?)
            (log/debug "BloodRetrograde released" "performed" performed?)))))
    (catch Exception e
      (log/warn "BloodRetrograde key-up failed:" (ex-message e)))))

(defn blood-retrograde-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (when-not (get-in ctx-data [:skill-state :ended?])
        (send-fx-end! ctx-id false)))
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "BloodRetrograde aborted")
    (catch Exception e
      (log/warn "BloodRetrograde key-abort failed:" (ex-message e)))))
