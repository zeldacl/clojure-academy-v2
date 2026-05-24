(ns cn.li.ac.content.ability.electromaster.current-charging-fx
  "Client FX for current-charging."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]))

(def ^:private visual-max-ticks 40)

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

(defonce ^:private current-state* (atom default-state))

(defn current-state
  []
  @current-state*)

(defn- now-ms []
  (System/currentTimeMillis))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double visual-max-ticks))]
    (max 0.0 (min 1.0 ratio))))

(defn- apply-start! [payload]
  (swap! current-state*
         (fn [state]
           (merge state
                  {:active? true
                   :blending? false
                   :is-item (boolean (:is-item payload))
                   :good? false
                   :charge-ticks 0
                   :charge-ratio 0.0
                   :target nil
                   :block-pos nil
                   :charged 0.0
                   :started-at-ms (now-ms)
                   :ending-at-ms 0})))
  (client-sounds/queue-sound-effect!
   {:type :sound
    :sound-id "my_mod:em.charge_loop"
    :volume 0.8
    :pitch 1.0}))

(defn- apply-update! [payload]
  (swap! current-state*
         (fn [state]
           (-> state
               (merge {:active? true
                       :blending? false})
               (cond-> (contains? payload :is-item)
                 (assoc :is-item (boolean (:is-item payload))))
               (cond-> (contains? payload :good?)
                 (assoc :good? (boolean (:good? payload))))
               (cond-> (contains? payload :charge-ticks)
                 (assoc :charge-ticks (max 0 (long (:charge-ticks payload)))
                        :charge-ratio (normalize-ratio (:charge-ticks payload))))
               (cond-> (contains? payload :target)
                 (assoc :target (:target payload)))
               (cond-> (contains? payload :block-pos)
                 (assoc :block-pos (:block-pos payload)))
               (cond-> (contains? payload :charged)
                 (assoc :charged (double (:charged payload))))))))

(defn- apply-end! [payload]
  (swap! current-state*
         (fn [state]
           (-> state
               (merge {:active? false
                       :blending? true
                       :is-item (boolean (:is-item payload))
                       :ending-at-ms (now-ms)})
               (assoc :good? false)))))

(defn- on-fx-channel [_ctx-id channel payload]
  (case channel
    :current-charging/fx-start (apply-start! payload)
    :current-charging/fx-update (apply-update! payload)
    :current-charging/fx-end (apply-end! payload)
    nil))

(defn init!
  []
  (reset! current-state* default-state)
  (fx-registry/register-fx-channels!
   [:current-charging/fx-start
    :current-charging/fx-update
    :current-charging/fx-end]
   on-fx-channel)
  nil)