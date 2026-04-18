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

(defn perform-resource!
  "Consume overload+cp from player's resource-data.
  Returns {:success? bool, :events events, :data new-resource-data}."
  ([player-id overload cp]
   (perform-resource! player-id overload cp false))
  ([player-id overload cp creative?]
   (if-let [state (ps/get-player-state player-id)]
     (let [{:keys [data success? events]} (res/perform-resource
                                          (:resource-data state)
                                          player-id
                                          (double overload)
                                          (double cp)
                                          (boolean creative?))]
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
  Returns true when cost is paid or no cost is defined."
  [spec stage evt]
  (let [cost-spec (get-in spec [:cost stage])]
    (if-not (map? cost-spec)
      true
      (let [computed (if (= :runtime-speed (:mode cost-spec))
                       (runtime-scaled-cost cost-spec)
                       cost-spec)
            cp (resolve-val (:cp computed) evt)
            overload (resolve-val (:overload computed) evt)
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
  "Apply exp gain from :exp-policy {:amount n :rate n} when present."
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
      (let [rate (double (or (get-in spec [:exp-policy :rate]) 1.0))
            value (resolve-val amount evt)]
        (add-skill-exp! (:player-id evt) skill-id value rate)))))

(defn emit-fx!
  "Emit fx stage for a context event."
  [spec evt stage]
  (fx/send! (:ctx-id evt) (:fx spec) stage evt))

