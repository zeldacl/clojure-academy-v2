(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill port aligned to original AcademyCraft behavior.

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private min-time 10)
(def ^:private max-time 40)
(def ^:private max-tolerant-time 100)

(def ^:private base-effects
  [{:effect :speed :max-amplifier 3}
   {:effect :jump-boost :max-amplifier 1}
   {:effect :regeneration :max-amplifier 1}
   {:effect :strength :max-amplifier 1}
   {:effect :resistance :max-amplifier 1}])

(defn- lerp
  [a b t]
  (+ a (* (- b a) (double t))))

(defn- get-probability
  [ct]
  (/ (- (double ct) 10.0) 18.0))

(defn- get-buff-time
  [ct exp]
  (int (* (+ 1.0 (rand))
          (double ct)
          (lerp 1.5 2.5 exp))))

(defn- get-hunger-buff-time
  [ct]
  (int (* 1.25 (double ct))))

(defn- get-buff-level
  [ct]
  (int (Math/floor (get-probability ct))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :body-intensify :exp] 0.0)))

(defn- send-fx-end!
  [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :body-intensify/fx-end {:performed? (boolean performed?)}))

(defn- consume-resource!
  [player-id overload cp creative?]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           overload
                                           cp
                                           creative?)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      {:success? (boolean success?)
       :resource-data data})))

(defn- enforce-overload-floor!
  [player-id floor-value]
  (ps/update-resource-data!
   player-id
   (fn [res-data]
     (if (< (double (:cur-overload res-data)) (double floor-value))
       (-> res-data
           (rdata/set-cur-overload floor-value)
           (assoc :overload-fine true))
       res-data))))

(defn- add-success-exp!
  [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :body-intensify
                                  0.01
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- set-body-intensify-cooldown!
  [player-id exp]
  (let [cooldown (int (Math/round (double (lerp 900.0 600.0 exp))))]
    (ps/update-cooldown-data! player-id cd/set-main-cooldown :body-intensify (max 1 cooldown))))

(defn- apply-body-intensify-buffs!
  [player-id charge-ticks exp]
  (when potion-effects/*potion-effects*
    (let [prob (get-probability charge-ticks)
          duration (get-buff-time charge-ticks exp)
          hunger-duration (get-hunger-buff-time charge-ticks)
          level (get-buff-level charge-ticks)
          shuffled-effects (vec (shuffle base-effects))]
      ;; Keep the same selection flow as original implementation.
      (loop [p prob
             picked 0]
        (when (> p 0.0)
          (let [roll (rand)]
            (if (< roll p)
              (let [next-picked (inc picked)]
                (when-let [{:keys [effect max-amplifier]} (nth shuffled-effects next-picked nil)]
                  (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                       player-id
                                                       effect
                                                       duration
                                                       (min level max-amplifier)))
                (recur (- p 1.0) next-picked))
              (recur (- p 1.0) picked)))))

      (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                           player-id
                                           :hunger
                                           hunger-duration
                                           2))))

(defn body-intensify-on-key-down
  "Consume initial overload and enter charging state."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          initial-overload (lerp 200.0 120.0 exp)
          {:keys [success? resource-data]} (consume-resource! player-id initial-overload 0.0 false)
          overload-floor (double (or (:cur-overload resource-data) initial-overload))]
      (if-not success?
        (do
          (send-fx-end! ctx-id false)
          (ctx/terminate-context! ctx-id nil)
          (log/debug "BodyIntensify start failed: insufficient resource"))
        (do
          (ctx/update-context! ctx-id assoc :skill-state
                               {:skip-default-cooldown true
                                :tick 0
                                :overload-floor overload-floor
                                :cp-per-tick (lerp 20.0 15.0 exp)
                                :exp exp})
          (log/debug "BodyIntensify charge started"))))
    (catch Exception e
      (log/warn "BodyIntensify key-down failed:" (ex-message e)))))

(defn body-intensify-on-key-tick
  "Consume CP while charging and stop at tolerance limit."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [tick (inc (int (or (:tick skill-state) 0)))
            cp-per-tick (double (or (:cp-per-tick skill-state) 20.0))
            overload-floor (double (or (:overload-floor skill-state) 0.0))]
        (enforce-overload-floor! player-id overload-floor)
        (ctx/update-context! ctx-id assoc-in [:skill-state :tick] tick)

        (cond
          (>= tick max-tolerant-time)
          (do
            (send-fx-end! ctx-id false)
            (ctx/terminate-context! ctx-id nil))

          (and (<= tick max-time)
            (not (:success? (consume-resource! player-id 0.0 cp-per-tick false))))
          (do
            (send-fx-end! ctx-id false)
            (ctx/terminate-context! ctx-id nil)))))
    (catch Exception e
      (log/warn "BodyIntensify key-tick failed:" (ex-message e)))))

(defn body-intensify-on-key-up
  "Apply randomized buffs on successful release."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [tick (int (or (:tick skill-state) 0))
            exp (double (or (:exp skill-state) (get-skill-exp player-id)))]
        (if (< tick min-time)
          (send-fx-end! ctx-id false)
          (let [effective-tick (min tick max-time)]
            (apply-body-intensify-buffs! player-id effective-tick exp)
            (add-success-exp! player-id)
            (set-body-intensify-cooldown! player-id exp)
            (send-fx-end! ctx-id true)
            (log/debug "BodyIntensify executed: tick" effective-tick)))))
    (catch Exception e
      (log/warn "BodyIntensify key-up failed:" (ex-message e)))))

(defn body-intensify-on-key-abort
  "Abort charging."
  [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "BodyIntensify charge aborted")
    (catch Exception e
      (log/warn "BodyIntensify key-abort failed:" (ex-message e)))))
