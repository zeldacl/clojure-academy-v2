(ns cn.li.ac.content.ability.electromaster.thunder-clap
  "ThunderClap skill - channeled AOE lightning strike.

  1:1 behavior aligned to original AcademyCraft ThunderClap:
  - Fixed charge window: 40..60 ticks
  - Start: consume overload lerp(390,252)
  - Tick: consume CP lerp(18,25) while ticks <= 40
  - Auto-cast at 60 ticks; release-cast when ticks >= 40
  - Targeting distance 40
  - Damage: lerp(36,72,exp) * lerp(1.0,1.2,(ticks-40)/60)
  - AOE radius: lerp(15,30,exp) with distance falloff
  - Cooldown: ticks * lerp(10,6,exp)
  - EXP gain on success: 0.003

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private min-ticks 40)
(def ^:private max-ticks 60)
(def ^:private eye-height 1.62)
(def ^:private target-distance 40.0)

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :thunder-clap :exp] 0.0)))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- try-consume-resource!
  [player-id overload cp]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           overload cp false)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- compute-damage [exp ticks]
  (let [base (lerp 36.0 72.0 exp)
        mult (lerp 1.0 1.2 (/ (- (double ticks) 40.0) 60.0))]
    (* base mult)))

(defn- compute-aoe-range [exp]
  (lerp 15.0 30.0 exp))

(defn- compute-cooldown [exp ticks]
  (int (* (double ticks) (lerp 10.0 6.0 exp))))

(defn- add-exp!
  [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :thunder-clap
                                  amount
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- current-eye-pos
  [player-id]
  (let [player-state (ps/get-player-state player-id)
        player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]
    {:x (double (:x player-pos))
     :y (+ (double (:y player-pos)) eye-height)
     :z (double (:z player-pos))}))

(defn- resolve-hit-pos
  [player-id world-id]
  (let [eye (current-eye-pos player-id)
        look (when raycast/*raycast*
               (raycast/get-player-look-vector raycast/*raycast* player-id))
        dx (double (or (:x look) 0.0))
        dy (double (or (:y look) 0.0))
        dz (double (or (:z look) 1.0))
        hit (when (and raycast/*raycast* look)
              (raycast/raycast-combined raycast/*raycast*
                                        world-id
                                        (:x eye) (:y eye) (:z eye)
                                        dx dy dz
                                        target-distance))
        dist (double (or (:distance hit) target-distance))
        ray-x (+ (:x eye) (* dx dist))
        ray-y (+ (:y eye) (* dy dist))
        ray-z (+ (:z eye) (* dz dist))]
    (if (= (:hit-type hit) :entity)
      {:x ray-x :y (+ ray-y eye-height) :z ray-z}
      {:x ray-x :y ray-y :z ray-z})))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id ticks hit-pos]
  (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-update
                           {:ticks ticks
                            :charge-ratio (clamp01 (/ (double ticks) (double max-ticks)))
                            :target hit-pos}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? (boolean performed?)}))

(defn- execute-thunder-clap!
  [{:keys [player-id ctx-id ticks hit-pos exp]}]
  (let [world-id (player-world-id player-id)
        aoe-range (compute-aoe-range exp)
        damage-max (compute-damage exp ticks)
        cooldown (max 1 (compute-cooldown exp ticks))
      {:keys [x y z]} hit-pos
      center-x (double x)
      center-y (double y)
      center-z (double z)]

    (when world-effects/*world-effects*
      (world-effects/spawn-lightning! world-effects/*world-effects* world-id x y z)

      ;; Match original attackRange behavior: radial falloff and exclude caster.
      (when entity-damage/*entity-damage*
          (doseq [{:keys [uuid x y z]}
                (world-effects/find-entities-in-radius world-effects/*world-effects* world-id x y z aoe-range)]
          (when (not= uuid player-id)
          (let [dx (- (double x) center-x)
            dy (- (double y) center-y)
            dz (- (double z) center-z)
                  dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                  factor (- 1.0 (clamp01 (/ dist (max 1.0e-6 aoe-range))))
                  applied (* damage-max factor)]
              (when (> applied 0.0)
                (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                    world-id uuid
                                                    applied :lightning)))))))

    (ps/update-cooldown-data! player-id cd/set-main-cooldown :thunder-clap cooldown)
    (add-exp! player-id 0.003)
    (send-fx-end! ctx-id true)
    (ctx/update-context! ctx-id update :skill-state assoc :performed? true)
    (log/debug "ThunderClap executed at" x y z
               "ticks" ticks
               "damage-max" (int damage-max)
               "aoe-range" (format "%.2f" aoe-range))))

(defn thunder-clap-on-key-down
  "Initialize charge state when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          overload (lerp 390.0 252.0 exp)
          started? (try-consume-resource! player-id overload 0.0)]
      (if-not started?
        (do
          (log/debug "ThunderClap start failed: insufficient resource")
          (ctx/terminate-context! ctx-id nil))
        (let [world-id (player-world-id player-id)
              hit-pos (resolve-hit-pos player-id world-id)]
          (ctx/update-context! ctx-id assoc :skill-state
                               {:ticks 0
                                :hit-pos hit-pos
                                :performed? false
                                :skip-default-cooldown true})
          (send-fx-start! ctx-id)
          (send-fx-update! ctx-id 0 hit-pos)
          (log/debug "ThunderClap charge started"))))
    (catch Exception e
      (log/warn "ThunderClap key-down failed:" (ex-message e)))))

(defn thunder-clap-on-key-tick
  "Update charge progress each tick."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            performed? (:performed? skill-state false)]
        (when-not performed?
          (let [exp (get-skill-exp player-id)
                world-id (player-world-id player-id)
                old-ticks (long (or (:ticks skill-state) 0))
                ticks (inc old-ticks)
                hit-pos (resolve-hit-pos player-id world-id)
                cp-consume (lerp 18.0 25.0 exp)
                cp-ok? (if (<= ticks min-ticks)
                         (try-consume-resource! player-id 0.0 cp-consume)
                         true)]

            (if-not cp-ok?
              (do
                (send-fx-end! ctx-id false)
                (ctx/terminate-context! ctx-id nil)
                (log/debug "ThunderClap aborted: insufficient CP at tick" ticks))
              (do
                (ctx/update-context! ctx-id update :skill-state assoc
                                     :ticks ticks
                                     :hit-pos hit-pos)
                (send-fx-update! ctx-id ticks hit-pos)

                (when (>= ticks max-ticks)
                  (execute-thunder-clap! {:player-id player-id
                                          :ctx-id ctx-id
                                          :ticks ticks
                                          :hit-pos hit-pos
                                          :exp exp})
                  (ctx/terminate-context! ctx-id nil))))))))
    (catch Exception e
      (log/warn "ThunderClap key-tick failed:" (ex-message e)))))

(defn thunder-clap-on-key-up
  "Execute ThunderClap when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            ticks (long (or (:ticks skill-state) 0))
            performed? (:performed? skill-state false)]
        (when-not performed?
          (if (< ticks min-ticks)
            (do
              (send-fx-end! ctx-id false)
              (log/debug "ThunderClap: insufficient charge, ticks" ticks))
            (let [exp (get-skill-exp player-id)
                  world-id (player-world-id player-id)
                  hit-pos (or (:hit-pos skill-state)
                              (resolve-hit-pos player-id world-id))]
              (execute-thunder-clap! {:player-id player-id
                                      :ctx-id ctx-id
                                      :ticks ticks
                                      :hit-pos hit-pos
                                      :exp exp}))))))
    (catch Exception e
      (log/warn "ThunderClap key-up failed:" (ex-message e)))))

(defn thunder-clap-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "ThunderClap charge aborted")
    (catch Exception e
      (log/warn "ThunderClap key-abort failed:" (ex-message e)))))
