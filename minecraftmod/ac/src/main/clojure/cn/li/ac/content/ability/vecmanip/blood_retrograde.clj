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
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def ^:private blood-retrograde-skill-id :blood-retrograde)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double blood-retrograde-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int blood-retrograde-skill-id field-id))

(defn- cfg-double-list [field-id]
  (skill-config/tunable-double-list blood-retrograde-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double blood-retrograde-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int blood-retrograde-skill-id field-id (exp01 exp)))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id blood-retrograde-skill-id))

(def ^:private world-up {:x 0.0 :y 1.0 :z 0.0})

(defn- get-player-look [player-id]
  (or (when (raycast/available?)
        (some-> (raycast/get-player-look-vector* player-id)
                geom/vnorm))
      {:x 0.0 :y 0.0 :z 1.0}))

(defn- get-target-info [world-id target-id fallback-hit]
  (let [fallback {:uuid target-id
                  :x (double (or (:x fallback-hit) 0.0))
                  :y (double (or (:y fallback-hit) 0.0))
                  :z (double (or (:z fallback-hit) 0.0))
                  :width (cfg-double :targeting.fallback-width)
                  :height (cfg-double :targeting.fallback-height)
                  :eye-height (cfg-double :targeting.fallback-eye-height)}]
    (or (when (and (world-effects/available?) target-id)
          (some (fn [{:keys [uuid] :as entity}]
                  (when (= uuid target-id)
                    (merge fallback entity)))
                (world-effects/find-entities-in-radius*
                                                       world-id
                                                       (:x fallback)
                                                       (:y fallback)
                                                       (:z fallback)
                                                       (cfg-double :targeting.entity-search-radius))))
        fallback)))

(defn- overload-cost [exp]
  (cfg-lerp :cost.release.overload exp))

(defn- cp-cost [exp]
  (cfg-lerp :cost.release.cp exp))

(defn- damage-value [exp]
  (cfg-lerp :combat.damage exp))

(defn- cooldown-ticks [exp]
  (cfg-lerp-int :cooldown.ticks exp))

(defn- update-skill-state-root!
  [ctx-id f & args]
  (apply ctx-skill/update-skill-state-root! ctx-id f args))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/update-skill-state-root! ctx-id identity state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn- release-hit
  [player-id ctx-id stage]
  (let [ticks (long (or (get-in (ctx/get-context ctx-id) [:skill-state :ticks]) 0))
        should-release? (case stage
                          :tick (>= (inc ticks) (cfg-int :charge.max-ticks))
                          :up true
                          false)]
    (when (and should-release? (raycast/available?))
      (raycast/raycast-from-player* player-id (cfg-double :targeting.distance) true))))

(defn- release-hit?
  [player-id ctx-id stage]
  (boolean (release-hit player-id ctx-id stage)))

(defn blood-retrograde-cost-release-tick
  [_]
  0.0)

(defn blood-retrograde-cost-release-cp
  [{:keys [player-id ctx-id]}]
  (if (release-hit? player-id ctx-id :up)
    (cp-cost (skill-exp player-id))
    0.0))

(defn blood-retrograde-cost-release-overload
  [{:keys [player-id ctx-id]}]
  (if (release-hit? player-id ctx-id :up)
    (overload-cost (skill-exp player-id))
    0.0))

(defn- spray-hit-payloads [player-id world-id target-info]
  (let [base-look (get-player-look player-id)
        head-pos {:x (:x target-info)
                  :y (+ (double (:y target-info)) (* (double (:height target-info)) 0.6))
                  :z (:z target-info)}]
    (->> (cfg-double-list :effect.spray-angles)
         (mapcat (fn [pitch-deg]
                   (let [yaw-jitter (- (* (rand) 40.0) 20.0)
                         yaw-turned (geom/rotate-around-axis base-look world-up yaw-jitter)
                         right-axis (let [raw (geom/vcross yaw-turned world-up)]
                                      (if (> (geom/vlen raw) 1.0e-5)
                                        (geom/vnorm raw)
                                        {:x 1.0 :y 0.0 :z 0.0}))
                         dir (geom/rotate-around-axis yaw-turned right-axis pitch-deg)
                         start (geom/v- head-pos (geom/v* dir 0.5))
                         hit (when (raycast/available?)
                               (raycast/raycast-blocks*
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
                          pos (geom/v+ (geom/v+ {:x (:x target-info)
                                                  :y (:y target-info)
                                                  :z (:z target-info)}
                                                dv)
                                       (geom/v* look-dir 0.2))]
                      {:x (:x pos)
                       :y (:y pos)
                       :z (:z pos)
                       :size (+ 1.4 (* (rand) 0.4))}))))))

(defn- send-fx-perform! [ctx-id player-id world-id target-id hit]
  (let [target-info (get-target-info world-id target-id hit)
        look-dir (get-player-look player-id)]
    (fx/send-perform! ctx-id :blood-retrograde/fx-perform
                      {:sound-pos {:x (:x target-info)
                                   :y (+ (double (:y target-info)) (* (double (:height target-info)) 0.5))
                                   :z (:z target-info)}
                       :splashes (splash-payloads look-dir target-info)
                       :sprays (spray-hit-payloads player-id world-id target-info)})))

(defn- finish! [ctx-id performed?]
  (update-skill-state-root! ctx-id
                            (fn [skill-state]
                              (assoc (or skill-state {})
                                     :executed? true
                                     :ended? true
                                     :performed? (boolean performed?))))
  (fx/send-end! ctx-id :blood-retrograde/fx-end {:performed? (boolean performed?)})
  (ctx/terminate-context! ctx-id nil))

(defn- try-perform! [player-id ctx-id hit cost-ok?]
  (let [target-id (:entity-id hit)
        world-id (geom/world-id-of player-id)
        exp (skill-exp player-id)]
    (when target-id
      (if-not cost-ok?
        false
        (do
          (skill-effects/set-main-cooldown! player-id :blood-retrograde (cooldown-ticks exp))
          (when (entity-damage/available?)
            (entity-damage/apply-direct-damage!*
                                                world-id
                                                target-id
                                                (damage-value exp)
                                                :generic))
          (skill-effects/add-skill-exp! player-id blood-retrograde-skill-id (cfg-double :progression.exp-hit))
          (send-fx-perform! ctx-id player-id world-id target-id hit)
          true)))))

(defn blood-retrograde-on-key-down
  "Initialize charge state and local charge slowdown."
  [{:keys [ctx-id]}]
  (set-skill-state-root! ctx-id
                         {:ticks 0
                          :executed? false
                          :ended? false})
  (fx/send-start! ctx-id :blood-retrograde/fx-start)
  (fx/send-update! ctx-id :blood-retrograde/fx-update {:ticks 0 :charge-ratio 0.0})
  (log/debug "BloodRetrograde charge started"))

(defn blood-retrograde-on-key-tick
  "Update charge progress and auto-release at max charge."
  [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)
          executed? (boolean (:executed? skill-state false))]
      (when-not executed?
        (let [ticks (inc (long (or (:ticks skill-state) 0)))]
          (update-skill-state-root! ctx-id #(assoc % :ticks ticks))
          (fx/send-update! ctx-id :blood-retrograde/fx-update
                           {:ticks (long ticks)
                            :charge-ratio (exp01 (/ (double ticks) (cfg-double :charge.fx-ratio-ticks)))})
          (when (>= ticks (cfg-int :charge.max-ticks))
            (let [hit (when (raycast/available?)
                        (raycast/raycast-from-player* player-id (cfg-double :targeting.distance) true))
                  performed? (boolean (and hit (try-perform! player-id ctx-id hit cost-ok?)))]
              (finish! ctx-id performed?)
              (log/debug "BloodRetrograde auto-release" "ticks" ticks "performed" performed?))))))))

(defn blood-retrograde-on-key-up
  "Execute the skill on release if a valid target is found."
  [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)
          executed? (boolean (:executed? skill-state false))]
      (when-not executed?
        (let [hit (when (raycast/available?)
              (raycast/raycast-from-player* player-id (cfg-double :targeting.distance) true))
              performed? (boolean (and hit (try-perform! player-id ctx-id hit cost-ok?)))]
          (finish! ctx-id performed?)
          (log/debug "BloodRetrograde released" "performed" performed?))))))

(defn blood-retrograde-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (when-not (get-in ctx-data [:skill-state :ended?])
      (fx/send-end! ctx-id :blood-retrograde/fx-end {:performed? false})))
  (clear-skill-state! ctx-id)
  (log/debug "BloodRetrograde aborted"))

(defskill blood-retrograde
  :id :blood-retrograde
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.blood_retrograde"
  :description-key "ability.skill.vecmanip.blood_retrograde.desc"
  :icon "textures/abilities/vecmanip/skills/blood_retro.png"
  :ui-position [204 83]
  :level 4
  :controllable? true
  :ctrl-id :blood-retrograde
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 90
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:tick {:cp blood-retrograde-cost-release-tick
               :overload blood-retrograde-cost-release-tick}
         :up {:cp blood-retrograde-cost-release-cp
              :overload blood-retrograde-cost-release-overload}}
  :actions {:down! blood-retrograde-on-key-down
            :tick! blood-retrograde-on-key-tick
            :up! blood-retrograde-on-key-up
            :abort! blood-retrograde-on-key-abort}
  :prerequisites [{:skill-id :directed-blastwave :min-exp 0.0}])

