(ns cn.li.ac.ability.service.skill-effects
  "Common side-effect helpers for skills.

  Centralizes the boilerplate:
  - resource consumption via res/perform-resource
  - player-state updates
  - firing ability events"
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defonce ^:private session-id-resolver*
  (atom (fn [] (runtime-hooks/player-state-session-id))))

(defn install-session-runtime!
  "Install runtime callback used for implicit session resolution in default arities.

  Keys:
  - :session-id-resolver (fn [] -> string|nil)"
  [{:keys [session-id-resolver]}]
  (when session-id-resolver
    (reset! session-id-resolver* session-id-resolver))
  nil)

(defn- resolve-session-id
  []
  (or ((or @session-id-resolver* (fn [] nil)))
      (runtime-hooks/require-player-state-session-id "skill-effects")))

(defn- runtime-player-state
  [player-id]
  (store/get-player-state* (resolve-session-id)
                           player-id))

(defn- runtime-player-state-in-session
  [session-id player-id]
  (store/get-player-state* session-id player-id))

(declare update-runtime-player-state-in-session!
         mark-runtime-dirty-in-session!
         perform-resource!
         perform-resource-in-session!
         add-skill-exp!
         add-skill-exp-in-session!
         set-main-cooldown!
         set-main-cooldown-in-session!
         apply-cost!
         apply-cooldown!
         enforce-overload-floor-in-session!
         enforce-overload-floor!
         assoc-player-path!
         assoc-player-path-in-session!
         update-player-path!
         update-player-path-in-session!
         gain-exp!
         emit-fx!
         get-player-state
         get-player-state-in-session!
         player-path
         player-path-in-session!
         skill-exp
         skill-exp-in-session!
         current-cp
         current-cp-in-session!)

(defn- update-runtime-player-state!
  [player-id f]
  (update-runtime-player-state-in-session! (resolve-session-id)
                                           player-id
                                           f))

(defn- update-runtime-player-state-in-session!
  [session-id player-id f]
  (store/update-player-state!* session-id player-id f))

(defn- mark-runtime-dirty!
  [player-id]
  (mark-runtime-dirty-in-session! (resolve-session-id)
                                  player-id))

(defn- mark-runtime-dirty-in-session!
  [session-id player-id]
  (store/mark-player-dirty! session-id player-id))


(defn scale-damage
  "Apply global and per-skill damage scaling to a raw damage value.
  spec is the skill spec map (with :damage-scale key, default 1.0)."
  [spec raw-damage]
  (* (double raw-damage)
      (double (cfg/damage-scale))
     (double (or (:damage-scale spec) 1.0))))

(defn perform-resource!
  "Consume overload+cp from player's resource-data.
  Returns {:success? bool, :events events, :data new-resource-data}."
  ([player-id overload cp]
   (perform-resource! player-id overload cp false))
  ([player-id overload cp creative?]
  (perform-resource-in-session! (resolve-session-id)
                                player-id
                                overload
                                cp
                                creative?)))

(defn perform-resource-in-session!
  [session-id player-id overload cp creative?]
  (if (runtime-player-state-in-session session-id player-id)
    (let [{:keys [state success? events]} (command-rt/run-command-in-session! session-id
                                                      player-id
                                                      {:command :consume-resource
                                                      :overload (double overload)
                                                      :cp (double cp)
                                                      :creative? (boolean creative?)})
           data (:resource-data state)]
       {:success? (boolean success?)
        :events events
        :data data})
     {:success? false
      :events []
    :data nil}))

(defn add-skill-exp!
  "Add exp to a skill and fire produced events."
  ([player-id skill-id amount]
   (add-skill-exp! player-id skill-id amount 1.0))
  ([player-id skill-id amount exp-rate]
    (add-skill-exp-in-session! (resolve-session-id)
                      player-id
                      skill-id
                      amount
                      exp-rate)))

(defn add-skill-exp-in-session!
  [session-id player-id skill-id amount exp-rate]
  (when (runtime-player-state-in-session session-id player-id)
    (let [scaled-amount (* (double amount) (double exp-rate))
          {:keys [state events]} (command-rt/run-command-in-session! session-id
                             player-id
                             {:command :add-skill-exp
                              :skill-id skill-id
                              :amount scaled-amount})
          data (:ability-data state)]
         {:data data :events events})))

(defn set-main-cooldown!
  "Set main cooldown for ctrl-id (or skill-id)."
  [player-id ctrl-id cooldown-ticks]
  (set-main-cooldown-in-session! (resolve-session-id)
                                 player-id
                                 ctrl-id
                                 cooldown-ticks)
  true)

(defn set-main-cooldown-in-session!
  [session-id player-id ctrl-id cooldown-ticks]
  (command-rt/run-command-in-session! session-id
                                      player-id
                                      {:command :set-cooldown
                                       :ctrl-id ctrl-id
                                       :sub-id :main
                                       :ticks (max 1 (int cooldown-ticks))})
  true)

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
    {:cp (* (cfg/runtime-cp-consume-per-tick) cp-speed)
     :overload (* (cfg/runtime-overload-per-tick) overload-speed)}))

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

(defn enforce-overload-floor!
  "Ensure player overload is not below floor-value.

  Returns true when player state exists; false otherwise."
  [player-id floor-value]
  (enforce-overload-floor-in-session! (resolve-session-id)
                                      player-id
                                      floor-value))

(defn enforce-overload-floor-in-session!
  [session-id player-id floor-value]
  (if (runtime-player-state-in-session session-id player-id)
    (do
      (update-runtime-player-state-in-session!
        session-id
        player-id
        (fn [player-state]
          (update player-state :resource-data
                  (fn [res-data]
                    (if (< (double (or (:cur-overload res-data) 0.0)) (double floor-value))
                      (-> res-data
                          (assoc :cur-overload (double floor-value))
                          (assoc :overload-fine true))
                      res-data)))))
      (mark-runtime-dirty-in-session! session-id player-id)
      true)
    false))

(defn assoc-player-path!
  "Associate value at arbitrary player-state path and mark dirty." 
  [player-id path value]
  (assoc-player-path-in-session! (resolve-session-id)
                                 player-id
                                 path
                                 value))

(defn assoc-player-path-in-session!
  [session-id player-id path value]
  (update-runtime-player-state-in-session! session-id player-id #(assoc-in % path value))
  (mark-runtime-dirty-in-session! session-id player-id)
  true)

(defn update-player-path!
  "Update value at arbitrary player-state path with f and args, then mark dirty."
  [player-id path f & args]
  (apply update-player-path-in-session!
         (resolve-session-id)
         player-id
         path
         f
         args))

(defn update-player-path-in-session!
  [session-id player-id path f & args]
  (update-runtime-player-state-in-session! session-id player-id #(apply update-in % path f args))
  (mark-runtime-dirty-in-session! session-id player-id)
  true)

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

(defn get-player-state
  "Return full player state map or nil when absent."
  [player-id]
  (get-player-state-in-session! (resolve-session-id)
                                player-id))

(defn get-player-state-in-session!
  [session-id player-id]
  (runtime-player-state-in-session session-id player-id))

(defn player-path
  "Read arbitrary path from player state with optional default value."
  ([player-id path]
    (player-path-in-session! (resolve-session-id) player-id path))
  ([player-id path default]
    (player-path-in-session! (resolve-session-id) player-id path default)))

(defn player-path-in-session!
  ([session-id player-id path]
   (get-in (runtime-player-state-in-session session-id player-id) path))
  ([session-id player-id path default]
   (get-in (runtime-player-state-in-session session-id player-id) path default)))

(defn skill-exp
  "Read clamped skill exp as double from ability-data."
  [player-id skill-id]
  (skill-exp-in-session! (resolve-session-id)
                         player-id
                         skill-id))

(defn skill-exp-in-session!
  [session-id player-id skill-id]
  (double (adata/get-skill-exp (:ability-data (runtime-player-state-in-session session-id player-id)) skill-id)))

(defn current-cp
  "Read current CP from resource-data as double."
  [player-id]
  (current-cp-in-session! (resolve-session-id)
                          player-id))

(defn current-cp-in-session!
  [session-id player-id]
  (double (or (player-path-in-session! session-id player-id [:resource-data :cur-cp] 0.0) 0.0)))



