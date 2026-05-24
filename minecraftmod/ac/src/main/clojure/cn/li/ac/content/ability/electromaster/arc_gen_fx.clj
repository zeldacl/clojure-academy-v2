(ns cn.li.ac.content.ability.electromaster.arc-gen-fx
  "Client FX for Arc-Gen: short electric arc beam and weak arc sound."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private sound-id "my_mod:em.arc_weak")
(def ^:private arc-life 10)

(defonce ^:private arcs* (atom []))

(defn- enqueue! [payload]
  (let [{:keys [mode start end hit-type]} payload]
    (case mode
      :perform
      (when (and (map? start) (map? end))
        (swap! arcs* conj {:start start
                           :end end
                           :hit-type hit-type
                           :ttl arc-life
                           :max-ttl arc-life})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0}))
      :end
      (reset! arcs* [])
      nil)))

(defn- tick! []
  (swap! arcs*
         (fn [items]
           (->> items
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec))))

(defn- arc-ops [cam-pos {:keys [start end ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        width (* 0.05 (+ 0.5 (* 0.5 life)))
        core-width (* width 0.45)
        outer-a (ru/with-alpha {:r 110 :g 190 :b 255} (int (+ 30 (* 180 life))))
        inner-a (ru/with-alpha {:r 210 :g 235 :b 255} (int (+ 60 (* 170 life))))
        line-a (ru/with-alpha {:r 180 :g 225 :b 255} (int (+ 50 (* 150 life))))]
    (ru/billboard-beam-ops cam-pos start end
                           {:width width
                            :core-width core-width
                            :outer-color outer-a
                            :inner-color inner-a
                            :line-color line-a})))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [items @arcs*
        ops (mapcat #(arc-ops camera-pos %) items)]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init! []
  (level-effects/register-level-effect! :arc-gen
    {:enqueue-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channel! :arc-gen/fx-perform
    (fn [_ctx-id _channel payload]
      (level-effects/enqueue-level-effect! :arc-gen
        {:mode :perform
         :start (:start payload)
         :end (:end payload)
         :hit-type (:hit-type payload)})))
  nil)
