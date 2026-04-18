(ns cn.li.ac.ability.server.service.skill-effects
  "Common side-effect helpers for skills.

  Centralizes the boilerplate:
  - resource consumption via res/perform-resource
  - player-state updates
  - firing ability events"
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.server.service.resource :as res]
            [cn.li.ac.ability.server.service.learning :as learning]
            [cn.li.ac.ability.server.service.cooldown :as cd]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.fx :as fx]))


(defn scale-damage
  "Apply global and per-skill damage scaling to a raw damage value.
  spec is the skill spec map (with :damage-scale key, default 1.0)."
  [spec raw-damage]
  (* (double raw-damage)
     (double cfg/*damage-scale*)
     (double (or (:damage-scale spec) 1.0))))

(defn perform-resource!
  "Consume overload+cp from player's resource-data.
  Returns {:success? bool, :events events, :data new-resource-data}."
  ([player-id overload cp]
   (perform-resource! player-id overload cp false))
  ([player-id overload cp creative?]
   (if-let [state (ps/get-player-state player-id)]
     (let [level (or (get-in state [:ability-data :level]) 1)
           {:keys [data success? events]} (res/perform-resource
                                          (:resource-data state)
                                          player-id
                                          (double overload)
                                          (double cp)
                                          (boolean creative?)
                                          level)]
       (when success?
         (ps/update-resource-data! player-id (constantly data))
         (doseq [e events] (evt/fire-ability-event! e)))
       {:success? (boolean success?)
        :events events
        :data data})
     {:success? false
      :events []
      :data nil})))

(defn add-skill-exp!
  "Add exp to a skill and fire produced events."
  ([player-id skill-id amount]
   (add-skill-exp! player-id skill-id amount 1.0))
  ([player-id skill-id amount exp-rate]
   (when-let [state (ps/get-player-state player-id)]
     (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  skill-id
                                  (double amount)
                                  (double exp-rate))]
       (ps/update-ability-data! player-id (constantly data))
       (doseq [e events] (evt/fire-ability-event! e))
       {:data data :events events}))))

(defn set-main-cooldown!
  "Set main cooldown for ctrl-id (or skill-id)."
  [player-id ctrl-id cooldown-ticks]
  (ps/update-cooldown-data! player-id cd/set-main-cooldown ctrl-id (max 1 (int cooldown-ticks))))

(defn- resolve-val
  [v evt]
  (cond
    (number? v) (double v)
    (fn? v) (double (or (v evt) 0.0))
    :else 0.0))

(defn- runtime-scaled-cost
  [cost-spec]
  (let [cp-speed (double (or (:cp-speed cost-spec) 1.0))
        overload-speed (double (or (:overload-speed cost-spec) 1.0))]
    {:cp (* cfg/*runtime-cp-consume-per-tick* cp-speed)
     :overload (* cfg/*runtime-overload-per-tick* overload-speed)}))

(defn apply-cost!
  "Apply stage cost for a skill spec and event.
  Scales cp/overload by the skill's per-skill speed multipliers.
  Returns true when cost is paid or no cost is defined."
  [spec stage evt]
  (let [cost-spec (get-in spec [:cost stage])]
    (if-not (map? cost-spec)
      true
      (let [computed (if (= :runtime-speed (:mode cost-spec))
                       (runtime-scaled-cost cost-spec)
                       cost-spec)
            cp-raw (resolve-val (:cp computed) evt)
            overload-raw (resolve-val (:overload computed) evt)
            ;; Apply per-skill speed multipliers from skill spec
            cp-speed (double (or (:cp-consume-speed spec) 1.0))
            ol-speed (double (or (:overload-consume-speed spec) 1.0))
            cp (* cp-raw cp-speed)
            overload (* overload-raw ol-speed)
            creative-raw (:creative? computed)
            creative? (boolean (if (fn? creative-raw) (creative-raw evt) creative-raw))]
        (if (and (zero? cp) (zero? overload))
          true
          (let [{:keys [success?]} (perform-resource! (:player-id evt) overload cp creative?)]
            (boolean success?)))))))

(defn apply-cooldown!
  "Apply cooldown for skill according to ctrl-id and cooldown-policy."
  [spec evt]
  (let [mode (get-in spec [:cooldown :mode])]
    (when (not= :manual mode)
      (let [player-id (:player-id evt)
            ctrl-id (or (:ctrl-id spec) (:id spec))
            base-ticks (or (get-in spec [:cooldown-policy :ticks]) (:cooldown-ticks spec) 1)
            ticks (int (max 1 (resolve-val base-ticks evt)))]
        (set-main-cooldown! player-id ctrl-id ticks)
        ticks))))

(defn gain-exp!
  "Apply exp gain from :exp-policy {:amount n :rate n} when present.
  Multiplies rate by the skill's :exp-incr-speed."
  [spec evt]
  (let [skill-id (:id spec)
        explicit-exp (:exp spec)
        effective? (boolean (:effective? evt))
        amount (cond
                 (map? explicit-exp)
                 (if effective?
                   (:effective explicit-exp)
                   (:ineffective explicit-exp))
                 :else (get-in spec [:exp-policy :amount]))]
    (when (and (some? amount) skill-id)
      (let [base-rate (double (or (get-in spec [:exp-policy :rate]) 1.0))
            skill-rate (double (or (:exp-incr-speed spec) 1.0))
            rate (* base-rate skill-rate)
            value (resolve-val amount evt)]
        (add-skill-exp! (:player-id evt) skill-id value rate)))))

(defn emit-fx!
  "Emit fx stage for a context event."
  [spec evt stage]
  (fx/send! (:ctx-id evt) (:fx spec) stage evt))

