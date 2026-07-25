(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
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
  :caster-pos nil
   :block-pos nil
   :charged 0.0
   :started-at-ms 0
  :ending-at-ms 0
  :updated-at-ms 0})

(def ^:private blend-out-ms 200)
(def ^:private active-stale-ms 500)









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
      (let [ts (now-ms)]
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
                          :started-at-ms ts
                          :ending-at-ms 0
                          :updated-at-ms ts})))

      :update
      (let [ts (now-ms)]
        (update-in store* [:states owner-key*]
                   (fn [state]
                     (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
                         (merge {:active? true
                                 :blending? false
                                 :updated-at-ms ts})
                         (cond-> (contains? payload* :is-item)
                           (assoc :is-item (boolean (:is-item payload*))))
                         (cond-> (contains? payload* :good?)
                           (assoc :good? (boolean (:good? payload*))))
                         (cond-> (contains? payload* :charge-ticks)
                           (assoc :charge-ticks (max 0 (long (:charge-ticks payload*)))
                                  :charge-ratio (normalize-ratio (:charge-ticks payload*))))
                         (cond-> (contains? payload* :target)
                           (assoc :target (:target payload*)))
                         (cond-> (contains? payload* :caster-pos)
                           (assoc :caster-pos (:caster-pos payload*)))
                         (cond-> (contains? payload* :block-pos)
                           (assoc :block-pos (:block-pos payload*)))
                         (cond-> (contains? payload* :charged)
                           (assoc :charged (double (:charged payload*))))))))

      :end
            (let [ts (now-ms)]
        (update-in store* [:states owner-key*]
             (fn [state]
               (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
             (merge {:active? false
               :blending? true
               :is-item (boolean (:is-item payload*))
               :charge-ticks 0
               :charge-ratio 0.0
               :ending-at-ms ts
               :updated-at-ms ts})
             (assoc :good? false)))))

      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        now-ms (now-ms)
        states' (into {}
                      (keep (fn [[owner-key st]]
                              (cond
              (and (:active? st)
                (< (- now-ms (long (or (:updated-at-ms st)
                        (:started-at-ms st)
                        0)))
                active-stale-ms))
                                [owner-key st]

                                (and (:blending? st)
                (< (- now-ms (long (or (:ending-at-ms st) 0))) blend-out-ms))
                                [owner-key st]

                                :else
                                nil)))
                      (:states store*))]
    (assoc store* :states states')))

(def ^:private charging-beam-style
  {:width 0.08
   :core-width 0.03
   :outer-color {:r 108 :g 228 :b 255 :a 120}
   :inner-color {:r 225 :g 250 :b 255 :a 180}
   :line-color {:r 160 :g 238 :b 255 :a 140}})

(defn- own-state?
  [st hand-center-pos]
  (or (nil? (:source-player-id st))
      (nil? (:player-uuid hand-center-pos))
      (= (str (:source-player-id st)) (str (:player-uuid hand-center-pos)))))

(defn- target-ring-ops
  [target ticks charge-ratio]
  (let [base-radius (+ 0.45 (* 0.25 (double charge-ratio)))
        pulse (+ base-radius (* 0.07 (Math/sin (* 0.24 (double ticks)))))
        tx (double (:x target))
        y (+ (double (:y target)) 0.05)
        tz (double (:z target))
        segments 24
        color {:r 204 :g 228 :b 255 :a 180}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 (rv3/v3 (+ tx (* pulse (Math/cos a0))) y (+ tz (* pulse (Math/sin a0))))
                  p1 (rv3/v3 (+ tx (* pulse (Math/cos a1))) y (+ tz (* pulse (Math/sin a1))))]]
        (ru/line-op p0 p1 color)))))

      (defn- caster-ring-ops
        [caster-pos ticks thin?]
        (let [radius (if thin? 0.45 0.65)
          pulse (+ radius (* 0.08 (Math/sin (* 0.22 (double ticks)))))
          cx (double (:x caster-pos))
          y (+ (double (:y caster-pos)) 0.9)
          cz (double (:z caster-pos))
          segments (if thin? 16 20)
          color (if thin?
              {:r 198 :g 238 :b 255 :a 130}
              {:r 170 :g 228 :b 255 :a 150})]
          (vec
        (for [idx (range segments)
          :let [a0 (/ (* 2.0 Math/PI idx) segments)
            a1 (/ (* 2.0 Math/PI (inc idx)) segments)
            p0 (rv3/v3 (+ cx (* pulse (Math/cos a0))) y (+ cz (* pulse (Math/sin a0))))
            p1 (rv3/v3 (+ cx (* pulse (Math/cos a1))) y (+ cz (* pulse (Math/sin a1))))]]
          (ru/line-op p0 p1 color)))))

(defn- build-plan
  [camera-pos hand-center-pos _tick]
  (let [store (:states (level-effects/effect-state-snapshot :current-charging))
        active-states (filter :active? (vals (or store {})))
        cam-v (rv3/map->v3 camera-pos)
        ops (vec
              (mapcat
                (fn [st]
                  (let [own? (own-state? st hand-center-pos)
                        start (or (and own? hand-center-pos)
                                  (:caster-pos st)
                                  hand-center-pos)
                        caster-pos (or (:caster-pos st)
                                       (some-> start (dissoc :player-uuid)))
                        target (:target st)
                        ticks (long (or (:charge-ticks st) 0))
                        ratio (double (or (:charge-ratio st) 0.0))
                        item? (boolean (:is-item st))
                        good? (boolean (:good? st))]
                    (concat
                      (when (and (not item?) (map? start) (map? target))
                        (fx-beam/beam-ops cam-v
                                          (rv3/map->v3 (dissoc start :player-uuid))
                                          (rv3/map->v3 target)
                                          charging-beam-style))
                      (when (and (not item?) good? (map? target))
                        (target-ring-ops target ticks ratio))
                      (when (map? caster-pos)
                        (caster-ring-ops caster-pos ticks item?)))))
                active-states))]
    (when (seq ops)
      {:ops ops})))

(defmethod arc-beam/effect-initial-state [:current-charging :hand] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :hand] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-initial-state [:current-charging :level] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :level] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-build-plan :current-charging
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod arc-beam/effect-clear-owner! :current-charging [_ store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
