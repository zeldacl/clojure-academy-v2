(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill - hold to charge randomised potions.

  Pattern: :charge-window (min 10, max 40, tolerant 100)
  Cost: overload lerp(200,120) on down; CP lerp(20,15)/tick while charging (�?0 ticks)
  Cooldown: lerp(900,600) ticks (manual, applied on successful up �?0 ticks)
  Exp: +0.01 on successful release"
  (:require [clojure.string :as str]
            [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def-skill-config-ops :body-intensify)
(def ^:private body-intensify-skill-id :body-intensify)

(defn- min-time [] (cfg-int :charge.min-ticks))
(defn- max-time [] (cfg-int :charge.max-ticks))
(defn- max-tolerant-time [] (cfg-int :charge.max-tolerant-ticks))

(defn- parse-effect-entry [entry]
  (let [[effect-name amp-text] (str/split (str entry) #":" 2)
        effect-name (str/trim (or effect-name ""))]
    (when-not (str/blank? effect-name)
      {:effect (keyword effect-name)
       :max-amplifier (max 0 (try
                               (Integer/parseInt (str/trim (or amp-text "0")))
                               (catch Exception _ 0)))})))

(defn- base-effects []
  (vec (keep parse-effect-entry
             (skill-config/tunable-string-list body-intensify-skill-id
                                               :effect.available-effects))))

(defn- get-probability [ct]
  (/ (- (double ct) (cfg-double :effect.probability-offset-ticks))
     (cfg-double :effect.probability-divisor)))

(defn- get-buff-time [ct exp]
  (int (* (+ 1.0 (rand)) (double ct) (cfg-lerp :effect.duration-multiplier exp))))

(defn- get-hunger-buff-time [ct]
  (int (* (cfg-double :effect.hunger-multiplier) (double ct))))

(defn- get-buff-level [ct] (int (Math/floor (get-probability ct))))

(defn- update-skill-state-root!
  [ctx-id f & args]
  (apply ctx-skill/update-skill-state-root! ctx-id f args))

(defn- enforce-overload-floor! [player-id floor-value]
  (skill-effects/enforce-overload-floor! player-id floor-value))

(defn- active-skill-ctx-data [player-id skill-id]
  (some (fn [[_ctx-id ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (= skill-id (:skill-id ctx-data)))
            ctx-data))
        (ctx/get-all-contexts)))

(defn- stored-hold-ticks [player-id skill-id]
  (long (or (get-in (active-skill-ctx-data player-id skill-id) [:skill-state :hold-ticks]) 0)))

(defn- down-overload-cost [_player-id _skill-id exp]
  (cfg-lerp :cost.down.overload (double (or exp 0.0))))

(defn- tick-cp-cost [player-id _skill-id exp]
  ;; Cost is evaluated before body-intensify-tick! increments the stored tick
  ;; count for this dispatch, so check against the tick this call is about to
  ;; produce (matches upstream's post-increment "tick <= MAX_TIME" check).
  (when (<= (inc (stored-hold-ticks player-id body-intensify-skill-id)) (max-time))
    (cfg-lerp :cost.tick.cp (double (or exp 0.0)))))

(defn- select-random-effects
  "Pick effects based on probability budget.

  The nth successful roll picks the nth effect from the shuffled pool,
  matching the legacy sequential-pick behavior without skipping index 0."
  [effects probability]
  (loop [p (double probability)
         picked-index 0
         selected []]
    (if (<= p 0.0)
      selected
      (if (< (rand) p)
        (let [selected* (if-let [effect-entry (nth effects picked-index nil)]
                          (conj selected effect-entry)
                          selected)]
          (recur (- p 1.0) (inc picked-index) selected*))
        (recur (- p 1.0) picked-index selected)))))

(defn- apply-body-intensify-buffs! [player-id charge-ticks exp]
  (when (potion-effects/available?)
    (let [prob            (get-probability charge-ticks)
          duration        (get-buff-time charge-ticks exp)
          hunger-duration (get-hunger-buff-time charge-ticks)
          level           (get-buff-level charge-ticks)
          shuffled        (vec (shuffle (base-effects)))]
      (doseq [{:keys [effect max-amplifier]}
              (select-random-effects shuffled prob)]
        (potion-effects/apply-potion-effect!*
         player-id effect duration (min level max-amplifier)))
      (potion-effects/apply-potion-effect!*
       player-id :hunger hunger-duration (cfg-int :effect.hunger-amplifier)))))

(defn- end-payload [ticks]
  {:performed? (>= (long ticks) (min-time))})

(defn- send-end-fx! [ctx-id ticks]
  (fx/send! ctx-id {:topic :body-intensify/fx-end :mode :end} nil (end-payload ticks)))

(defn- body-intensify-cost-fail!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (send-end-fx! ctx-id (stored-hold-ticks player-id body-intensify-skill-id))
  (ctx/terminate-context! ctx-id nil))

(defn- body-intensify-tick!
  "The generic dispatch pipeline's hold-ticks argument is never populated for
  server-tick-driven charge-window contexts (cn.li.ac.ability.service.context-manager's
  tick-context-entry! passes {:ctx-id :skill-id} only, no :hold-ticks) — so this
  self-tracks the charge duration in :skill-state instead of trusting the
  argument, matching railgun.clj/scatter_bomb.clj/mark_teleport.clj."
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [ticks (inc (stored-hold-ticks player-id body-intensify-skill-id))]
    (update-skill-state-root! ctx-id assoc :hold-ticks ticks)
    (enforce-overload-floor! player-id (cfg-lerp :cost.down.overload (double (or exp 0.0))))
    (when (>= ticks (max-tolerant-time))
      (send-end-fx! ctx-id ticks)
      (ctx/terminate-context! ctx-id nil))))

(defn- body-intensify-up!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [ticks (stored-hold-ticks player-id body-intensify-skill-id)]
    (when (>= ticks (min-time))
      (let [effective-tick (min ticks (max-time))
            exp*           (double (or exp 0.0))]
        (apply-body-intensify-buffs! player-id effective-tick exp*)
        (skill-effects/add-skill-exp! player-id body-intensify-skill-id
                                      (cfg-double :progression.exp-use))
        (skill-effects/set-main-cooldown! player-id body-intensify-skill-id
                                          (skill-config/lerp-int body-intensify-skill-id
                                                                 :cooldown.ticks
                                                                 exp*))))
    (send-end-fx! ctx-id ticks)))

(defskill body-intensify
  :id          :body-intensify
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.body_intensify"
  :description-key "ability.skill.electromaster.body_intensify.desc"
  :icon        "textures/abilities/electromaster/skills/body_intensify.png"
  :ui-position [97 15]
  :ctrl-id     :body-intensify
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:down {:overload down-overload-cost}
                :tick {:cp tick-cp-cost}}
  :actions
  {:cost-fail! body-intensify-cost-fail!
   :tick!      body-intensify-tick!
   :up!        body-intensify-up!}
  :prerequisites [{:skill-id :arc-gen         :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 1.0}])
