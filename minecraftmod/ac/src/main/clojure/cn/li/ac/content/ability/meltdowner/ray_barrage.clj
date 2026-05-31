(ns cn.li.ac.content.ability.meltdowner.ray-barrage
  "RayBarrage skill - dual branch behavior.

  Branch A (plain): normal direct beam.
  Branch B (scattered): when first hit is an available silbarn, fire multiple
  scattered beams around silbarn's nearby targets.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private ray-barrage-skill-id :ray-barrage)

(defn- cfg-double [field-id]
  (skill-config/tunable-double ray-barrage-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int ray-barrage-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double ray-barrage-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int ray-barrage-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id ray-barrage-skill-id))

(defn reset-ray-barrage-state-for-test!
  []
  nil)

(defn- silbarn-type?
  [entity-type]
  (let [s (some-> entity-type str str/lower-case)]
    (boolean (and s (str/includes? s "silbarn")))))

(defn- normalize-look-dir
  [look-vec]
  (let [dx (double (or (:dx look-vec) (:x look-vec) 0.0))
        dy (double (or (:dy look-vec) (:y look-vec) 0.0))
        dz (double (or (:dz look-vec) (:z look-vec) 1.0))
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
    {:dx (/ dx len) :dy (/ dy len) :dz (/ dz len)}))

(defn- target-look-dir
  [eye target]
  (normalize-look-dir {:x (- (double (:x target)) (double (:x eye)))
                       :y (- (double (+ (:y target) (or (:eye-height target) 0.0)))
                             (double (:y eye)))
                       :z (- (double (:z target)) (double (:z eye)))}))

(defn- beam-spec
  [damage]
  {:radius          (cfg-double :beam.radius)
   :query-radius    (cfg-double :beam.query-radius)
   :step            (cfg-double :beam.step)
   :max-distance    (cfg-double :beam.max-distance)
   :visual-distance (cfg-double :beam.visual-distance)
   :damage          (double damage)
   :damage-type     :magic
   :break-blocks?   false
   :block-energy    0.0
   :fx-topic        :ray-barrage/fx-beam})

(defn- run-beam!
  [{:keys [player-id ctx-id world-id eye look-dir damage]}]
  (beam/execute-beam!
   {:player-id player-id
    :ctx-id ctx-id
    :world-id world-id
    :eye-pos eye
    :look-dir look-dir}
   (beam-spec damage)))

(defn- send-fx-to-local-and-nearby!
  [ctx-id channel payload]
  (ctx/ctx-send-to-client! ctx-id channel payload)
  (ctx/ctx-send-to-except-local! ctx-id channel payload))

(defn- send-preray-fx!
  [ctx-id eye target-end hit?]
  (send-fx-to-local-and-nearby!
    ctx-id
    :ray-barrage/fx-preray
    {:start {:x (:x eye) :y (:y eye) :z (:z eye)}
     :end {:x (:x target-end) :y (:y target-end) :z (:z target-end)}
     :hit? (boolean hit?)}))

(defn- send-barrage-fx!
  [ctx-id silbarn-hit scatter-count]
  (send-fx-to-local-and-nearby!
    ctx-id
    :ray-barrage/fx-barrage
    {:silbarn {:x (double (or (:x silbarn-hit) (:hit-x silbarn-hit) 0.0))
               :y (double (or (:y silbarn-hit) (:hit-y silbarn-hit) 0.0))
               :z (double (or (:z silbarn-hit) (:hit-z silbarn-hit) 0.0))}
     :scatter-count (int scatter-count)}))

(defn- front-hit-end
  [eye look-dir front-hit]
  (if (and (map? front-hit)
           (some? (or (:x front-hit) (:hit-x front-hit)))
           (some? (or (:y front-hit) (:hit-y front-hit)))
           (some? (or (:z front-hit) (:hit-z front-hit))))
    {:x (double (or (:x front-hit) (:hit-x front-hit)))
     :y (double (or (:y front-hit) (:hit-y front-hit)))
     :z (double (or (:z front-hit) (:hit-z front-hit)))}
    (let [dist (double (cfg-double :targeting.range))]
      {:x (+ (double (:x eye)) (* dist (double (:dx look-dir))))
       :y (+ (double (:y eye)) (* dist (double (:dy look-dir))))
       :z (+ (double (:z eye)) (* dist (double (:dz look-dir))))})))

(defn- raycast-front-hit
  [world-id eye look-dir]
  (when raycast/*raycast*
    (raycast/raycast-combined raycast/*raycast*
                              world-id
                              (double (:x eye)) (double (:y eye)) (double (:z eye))
                              (double (:dx look-dir)) (double (:dy look-dir)) (double (:dz look-dir))
                              (double (cfg-double :targeting.range)))))

(defn- select-scatter-targets
  [world-id silbarn-hit player-id]
  (if-not world-effects/*world-effects*
    []
    (let [sx (double (or (:x silbarn-hit) (:hit-x silbarn-hit) 0.0))
          sy (double (or (:y silbarn-hit) (:hit-y silbarn-hit) 0.0))
          sz (double (or (:z silbarn-hit) (:hit-z silbarn-hit) 0.0))
          silbarn-uuid (some-> (:uuid silbarn-hit) str)
          targets (->> (world-effects/find-entities-in-radius
                         world-effects/*world-effects*
                         world-id sx sy sz
                         (double (cfg-double :scatter.target-radius)))
                       (remove (fn [{:keys [uuid]}]
                                 (or (= (str uuid) (str player-id))
                                     (= (str uuid) silbarn-uuid))))
                       (sort-by (fn [{:keys [x y z]}]
                                  (let [dx (- (double x) sx)
                                        dy (- (double y) sy)
                                        dz (- (double z) sz)]
                                    (+ (* dx dx) (* dy dy) (* dz dz)))))
                       vec)
          max-count (max 0 (int (cfg-int :scatter.count)))]
      (if (<= max-count 0)
        []
        (take max-count (cycle targets))))))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn ray-barrage-perform!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp            (skill-exp player-id)
          plain-damage   (cfg-lerp :combat.damage.plain exp)
          scatter-damage (cfg-lerp :combat.damage.scattered exp)
          world-id       (geom/world-id-of player-id)
          eye            (geom/eye-pos player-id)
          look-vec       (when raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))
          look-dir       (when look-vec (normalize-look-dir look-vec))]
      (when look-dir
        (let [front-hit     (raycast-front-hit world-id eye look-dir)
              silbarn-hit?  (and (= :entity (:hit-type front-hit))
                                 (silbarn-type? (:type front-hit)))
              silbarn-ready? (and silbarn-hit?
                                  (not (true? (:is-hit front-hit))))
              hit-count*    (atom 0)]
          (if silbarn-ready?
            (do
              (send-preray-fx! ctx-id eye front-hit true)
              (let [targets (vec (select-scatter-targets world-id front-hit player-id))]
                (send-barrage-fx! ctx-id front-hit (count targets))
                (doseq [target targets]
                  (let [result (run-beam! {:player-id player-id
                                           :ctx-id ctx-id
                                           :world-id world-id
                                           :eye eye
                                           :look-dir (target-look-dir eye target)
                                           :damage scatter-damage})]
                    (when (get-in result [:beam-result :performed?])
                      (doseq [target-id (or (get-in result [:beam-result :hit-uuids]) [])]
                        (md-damage/mark-target! player-id target-id {:ctx-id ctx-id}))
                      (swap! hit-count* inc))))))
            (let [result (run-beam! {:player-id player-id
                                     :ctx-id ctx-id
                                     :world-id world-id
                                     :eye eye
                                     :look-dir look-dir
                                     :damage plain-damage})]
              (send-preray-fx! ctx-id eye (front-hit-end eye look-dir front-hit) false)
              (when (get-in result [:beam-result :performed?])
                (doseq [target-id (or (get-in result [:beam-result :hit-uuids]) [])]
                  (md-damage/mark-target! player-id target-id {:ctx-id ctx-id}))
                (swap! hit-count* inc))))
          (when (pos? @hit-count*)
            (skill-effects/add-skill-exp! player-id ray-barrage-skill-id
                                          (cfg-double :progression.exp-hit))
            (log/debug "RayBarrage: hit" @hit-count* "beams")))))
    (catch Exception e
      (log/warn "RayBarrage perform! failed:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill ray-barrage
  :id             :ray-barrage
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.ray_barrage"
  :description-key "ability.skill.meltdowner.ray_barrage.desc"
  :icon           "textures/abilities/meltdowner/skills/ray_barrage.png"
  :ui-position    [60 200]
  :level          4
  :controllable?  false
  :ctrl-id        :ray-barrage
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))} }
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (cfg-lerp-int :cooldown.ticks (skill-exp player-id)))
  :actions        {:perform! ray-barrage-perform!}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.5}])

