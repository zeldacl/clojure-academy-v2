(ns cn.li.ac.content.ability.electromaster.current-charging-fx
  "Client FX for current-charging."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- visual-max-ticks
  "Read the visual-max-ticks for current-charging from skill config.
   Falls back to 40 ticks (2 seconds at 20 tps)."
  []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))
(def ^:private current-charging-effect-id :current-charging)

(def ^:private default-state
  {:active? false
   :blending? false
   :is-item false
   :good? false
   :charge-ticks 0
   :charge-ratio 0.0
   :target nil
   :block-pos nil
   :charged 0.0
   :started-at-ms 0
   :ending-at-ms 0})

(defn default-current-charging-fx-runtime-state
  []
  {:states {}})

(defn current-charging-fx-snapshot
  []
  (or (hand-effects/effect-state-snapshot current-charging-effect-id)
      (default-current-charging-fx-runtime-state)))

(defn reset-current-charging-fx-for-test!
  []
  (hand-effects/reset-hand-effect-state-for-test!
    current-charging-effect-id
    (default-current-charging-fx-runtime-state))
  nil)

(defn clear-current-charging-owner!
  [owner-key]
  (hand-effects/update-effect-state!
    current-charging-effect-id
    (fn [store]
      (let [store* (if (contains? (or store {}) :states)
                     (or store (default-current-charging-fx-runtime-state))
                     (default-current-charging-fx-runtime-state))]
        (assoc store* :states (dissoc (:states store*) owner-key)))))
  nil)

(defn- current-store []
  (let [store (current-charging-fx-snapshot)]
    (if (contains? store :states)
      store
      (default-current-charging-fx-runtime-state))))

(defn- state-for-selector [store selector]
  (let [states (:states store)]
    (or (cond
          (vector? selector)
          (get states selector)

          (some? selector)
          (some (fn [[_ st]]
                  (when (and (:source-player-id st)
                             (= (str selector) (str (:source-player-id st))))
                    st))
                states)

          :else
          (or (some (fn [[_ st]]
                      (when (:active? st) st))
                    states)
              (some (fn [[_ st]]
                      (when (:blending? st) st))
                    states)))
        default-state)))

(defn current-state
  [selector]
  (state-for-selector (current-store) selector))

(defn- now-ms []
  ;; Use game-time so charge animations pause with the game.
  (client-bridge/game-time-ms))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double (visual-max-ticks)))]
    (max 0.0 (min 1.0 ratio))))

(defn- owner-key [ctx-id payload]
  (or (:owner-key payload)
      (when-let [source-player-id (:source-player-id payload)]
        [:source-player source-player-id])
      [:ctx ctx-id]))

(defn- base-meta [owner-key ctx-id payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

(defn- enqueue-state! [store payload]
  (let [store* (if (contains? (or store {}) :states)
                 (or store (default-current-charging-fx-runtime-state))
                 (default-current-charging-fx-runtime-state))
        {:keys [mode ctx-id] :as payload*} (or payload {})
        owner-key* (owner-key ctx-id payload*)]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound
           :sound-id "my_mod:em.charge_loop"
           :volume 0.8
           :pitch 1.0})
        (assoc-in store* [:states owner-key*]
                  (merge default-state
                         (base-meta owner-key* ctx-id payload*)
                         {:active? true
                          :blending? false
                          :is-item (boolean (:is-item payload*))
                          :good? false
                          :charge-ticks 0
                          :charge-ratio 0.0
                          :target nil
                          :block-pos nil
                          :charged 0.0
                          :started-at-ms (now-ms)
                          :ending-at-ms 0})))

      :update
      (update-in store* [:states owner-key*]
                 (fn [state]
                   (-> (merge default-state state (base-meta owner-key* ctx-id payload*))
                       (merge {:active? true
                               :blending? false})
                       (cond-> (contains? payload* :is-item)
                         (assoc :is-item (boolean (:is-item payload*))))
                       (cond-> (contains? payload* :good?)
                         (assoc :good? (boolean (:good? payload*))))
                       (cond-> (contains? payload* :charge-ticks)
                         (assoc :charge-ticks (max 0 (long (:charge-ticks payload*)))
                                :charge-ratio (normalize-ratio (:charge-ticks payload*))))
                       (cond-> (contains? payload* :target)
                         (assoc :target (:target payload*)))
                       (cond-> (contains? payload* :block-pos)
                         (assoc :block-pos (:block-pos payload*)))
                       (cond-> (contains? payload* :charged)
                         (assoc :charged (double (:charged payload*)))))))

      :end
      (update-in store* [:states owner-key*]
                 (fn [state]
                   (-> (merge default-state state (base-meta owner-key* ctx-id payload*))
                       (merge {:active? false
                               :blending? true
                               :is-item (boolean (:is-item payload*))
                               :ending-at-ms (now-ms)})
                       (assoc :good? false))))

      store*)))

(defn- tick-state!
  [store]
  (if (contains? (or store {}) :states)
    (or store (default-current-charging-fx-runtime-state))
    (default-current-charging-fx-runtime-state)))

(defn init!
  []
  (fx-spec/register!
    {:id current-charging-effect-id
     :hand {:initial-state (default-current-charging-fx-runtime-state)
            :enqueue-state-fn enqueue-state!
            :tick-state-fn tick-state!}
     :channels {:start {:topic :current-charging/fx-start :mode :start :targets [:hand]}
                :update {:topic :current-charging/fx-update :mode :update :targets [:hand]}
                :end {:topic :current-charging/fx-end :mode :end :targets [:hand]}}})
  (hand-effects/reset-hand-effect-state-for-test!
    current-charging-effect-id
    (default-current-charging-fx-runtime-state))
  nil)
