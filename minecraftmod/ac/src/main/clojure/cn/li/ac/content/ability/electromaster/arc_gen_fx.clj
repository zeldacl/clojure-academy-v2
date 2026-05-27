(ns cn.li.ac.content.ability.electromaster.arc-gen-fx
  "Client FX for Arc-Gen: short electric arc beam and weak arc sound."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private sound-id "my_mod:em.arc_weak")
(def ^:private arc-life 10)

(defonce ^:private arcs* (atom {}))

(defn arc-gen-fx-snapshot
  []
  {:arcs @arcs*})

(defn reset-arc-gen-fx-for-test!
  []
  (reset! arcs* {})
  nil)

(defn clear-arc-gen-owner!
  [owner-key]
  (swap! arcs* dissoc owner-key)
  nil)

(defn- all-arcs []
  (mapcat val @arcs*))

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-type source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :perform
      (when (and (map? start) (map? end))
        (swap! arcs* update owner-key* (fnil conj [])
               (merge base-meta
                      {:start start
                       :end end
                       :hit-type hit-type
                       :ttl arc-life
                       :max-ttl arc-life}))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0}))
      :end
      (swap! arcs* dissoc owner-key*)
      nil)))

(defn- tick! []
  (swap! arcs*
         (fn [by-owner]
           (into {}
                 (keep (fn [[owner-key items]]
                         (let [live (->> items
                                         (map #(update % :ttl dec))
                                         (filter #(pos? (long (:ttl %))))
                                         vec)]
                           (when (seq live)
                             [owner-key live]))))
                 by-owner))))

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
  (let [items (all-arcs)
        ops (mapcat #(arc-ops camera-pos %) items)]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init! []
  (level-effects/register-level-effect! :arc-gen
    {:enqueue-event-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channel! :arc-gen/fx-perform
    (fn [ctx-id channel payload]
      (level-effects/enqueue-level-effect! :arc-gen
        (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
               {:mode :perform
                :start (:start payload)
                :end (:end payload)
                :hit-type (:hit-type payload)})
        {:ctx-id ctx-id :channel channel})))
  nil)
