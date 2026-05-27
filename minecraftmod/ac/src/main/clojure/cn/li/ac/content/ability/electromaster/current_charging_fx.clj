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

(defn- empty-store []
  {:states {}
   :current-owner-key nil})

(defonce ^:private current-state* (atom (empty-store)))

(defn current-charging-fx-snapshot
  []
  @current-state*)

(defn reset-current-charging-fx-for-test!
  []
  (reset! current-state* (empty-store))
  nil)

(defn clear-current-charging-owner!
  [owner-key]
  (swap! current-state*
         (fn [store]
           (let [states (dissoc (:states store) owner-key)]
             {:states states
              :current-owner-key (when-not (= owner-key (:current-owner-key store))
                                   (:current-owner-key store))})))
  nil)

(defn- current-store []
  (let [store @current-state*]
    (if (contains? store :states)
      store
      (empty-store))))

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
          (or (get states (:current-owner-key store))
              (some (fn [[_ st]]
                      (when (:active? st) st))
                    states)))
        default-state)))

(defn current-state
  ([]
   (current-state nil))
  ([selector]
   (state-for-selector (current-store) selector)))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double visual-max-ticks))]
    (max 0.0 (min 1.0 ratio))))

(defn- owner-key [ctx-id payload]
  (or (:owner-key payload)
      [:ctx ctx-id]))

(defn- base-meta [owner-key ctx-id payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

(defn- apply-start! [owner-key ctx-id payload]
  (swap! current-state*
         (fn [store]
           (-> (if (contains? store :states) store (empty-store))
               (assoc-in [:states owner-key]
                         (merge default-state
                                (base-meta owner-key ctx-id payload)
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
                                 :ending-at-ms 0}))
               (assoc :current-owner-key owner-key))))
  (client-sounds/queue-current-sound-effect!
   {:type :sound
    :sound-id "my_mod:em.charge_loop"
    :volume 0.8
    :pitch 1.0}))

(defn- apply-update! [owner-key ctx-id payload]
  (swap! current-state*
         (fn [store]
           (let [store* (if (contains? store :states) store (empty-store))]
             (-> store*
                 (update-in [:states owner-key]
                            (fn [state]
                              (-> (merge default-state state (base-meta owner-key ctx-id payload))
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
                                    (assoc :charged (double (:charged payload)))))))
                 (assoc :current-owner-key owner-key))))))

(defn- apply-end! [owner-key ctx-id payload]
  (swap! current-state*
         (fn [store]
           (let [store* (if (contains? store :states) store (empty-store))]
             (-> store*
                 (update-in [:states owner-key]
                            (fn [state]
                              (-> (merge default-state state (base-meta owner-key ctx-id payload))
                                  (merge {:active? false
                                          :blending? true
                                          :is-item (boolean (:is-item payload))
                                          :ending-at-ms (now-ms)})
                                  (assoc :good? false))))
                 (assoc :current-owner-key owner-key))))))

(defn- on-fx-channel [ctx-id channel payload]
  (let [payload* (or payload {})
        owner-key* (owner-key ctx-id payload*)]
    (case channel
      :current-charging/fx-start (apply-start! owner-key* ctx-id payload*)
      :current-charging/fx-update (apply-update! owner-key* ctx-id payload*)
      :current-charging/fx-end (apply-end! owner-key* ctx-id payload*)
      nil)))

(defn init!
  []
  (reset! current-state* (empty-store))
  (fx-registry/register-fx-channels!
   [:current-charging/fx-start
    :current-charging/fx-update
    :current-charging/fx-end]
   on-fx-channel)
  nil)