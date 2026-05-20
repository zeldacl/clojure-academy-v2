(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx
  "Client FX for Thunder Bolt: electric arc effects."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private arcs (atom []))
(def ^:private main-arc-life 20)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [start end aoe-points]} payload]
    (when (and start end)
      (dotimes [_ 3]
        (swap! arcs conj {:start start :end end
                          :ttl main-arc-life :max-ttl main-arc-life
                          :is-aoe? false}))
      (doseq [pt aoe-points]
        (when (map? pt)
          (let [life (+ 15 (rand-int 11))]
            (swap! arcs conj {:start end :end pt
                              :ttl life :max-ttl life
                              :is-aoe? true}))))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:em.arc_strong" :volume 0.6 :pitch 1.0}))))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! arcs
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- arc-ops [cam-pos {:keys [start end ttl max-ttl is-aoe?]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        width (if is-aoe?
                (* 0.04 (+ 0.4 (* 0.6 life)))
                (* 0.07 (+ 0.5 (* 0.5 life))))
        core-width (* width 0.4)
        outer-a (ru/with-alpha {:r 200 :g 230 :b 255} (int (+ 40 (* 180 life))))
  inner-a (ru/with-alpha {:r 255 :g 255 :b 255} (int (+ 60 (* 180 life))))]
    (ru/billboard-beam-ops cam-pos start end
      {:width width
       :core-width core-width
       :outer-color outer-a
       :inner-color inner-a
       :line-color (ru/with-alpha {:r 160 :g 220 :b 255} (int (+ 60 (* 140 life))))})))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [current-arcs @arcs
        plan (mapcat #(arc-ops camera-pos %) current-arcs)]
    (when (seq plan)
      {:ops (vec plan)})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :thunder-bolt-strike
    {:enqueue-fn    enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channel! :thunder-bolt/fx-perform
    (fn [_ctx-id _channel payload]
      (level-effects/enqueue-level-effect! :thunder-bolt-strike payload)))
  nil)
