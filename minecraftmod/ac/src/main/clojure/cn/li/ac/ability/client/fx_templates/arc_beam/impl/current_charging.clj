(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [clojure.string :as str]))

(defn- visual-max-ticks
  "Read the visual-max-ticks for current-charging from skill config.
   Falls back to 40 ticks (2 seconds at 20 tps)."
  []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))


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









(defn- current-store []
  (let [store (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :current-charging)]
    (if (contains? store :states)
      store
      {:states {}})))

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



(defn- now-ms []
  ;; Use game-time so charge animations pause with the game.
  (client-bridge/game-time-ms))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double (visual-max-ticks)))]
    (max 0.0 (min 1.0 ratio))))

(defn- resolve-owner-key [ctx-id _channel explicit-owner-key payload]
  (or explicit-owner-key
      (when-let [source-player-id (:source-player-id payload)]
        [:source-player source-player-id])
      [:ctx ctx-id]))

(defn- base-meta [owner-key ctx-id channel payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :channel channel
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

(defn- enqueue-state! [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        {:keys [mode] :as payload*} (or payload {})
        owner-key* (resolve-owner-key ctx-id channel owner-key payload*)]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound
           :sound-id (modid/namespaced-path "em.charge_loop")
           :volume 0.8
           :pitch 1.0})
        (assoc-in store* [:states owner-key*]
                  (merge default-state
                         (base-meta owner-key* ctx-id channel payload*)
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
                   (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
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
                   (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
                       (merge {:active? false
                               :blending? true
                               :is-item (boolean (:is-item payload*))
                               :ending-at-ms (now-ms)})
                       (assoc :good? false))))

      store*)))

(defn- tick-state!
  [store]
  (if (contains? (or store {}) :states)
    (or store {:states {}})
    {:states {}}))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:current-charging :hand] [_ _] {:states {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:current-charging :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:current-charging :hand] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :current-charging [_ store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
