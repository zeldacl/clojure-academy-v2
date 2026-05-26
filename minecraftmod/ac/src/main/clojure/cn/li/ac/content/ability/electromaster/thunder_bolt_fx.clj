(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx
  "Client FX for Thunder Bolt: electric arc effects."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private arcs (atom {}))
(def ^:private main-arc-life 20)

(defn thunder-bolt-fx-snapshot
  []
  {:arcs @arcs})

(defn reset-thunder-bolt-fx-for-test!
  []
  (reset! arcs {})
  nil)

(defn clear-thunder-bolt-owner!
  [owner-key]
  (swap! arcs dissoc owner-key)
  nil)

(defn- all-arcs
  []
  (mapcat val @arcs))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [{:keys [start end aoe-points source-player-id world-id]} payload
        owner-key* (or owner-key [:ctx ctx-id])]
    (when (and start end)
      (let [base-meta {:owner-key owner-key*
                       :ctx-id ctx-id
                       :channel channel
                       :source-player-id source-player-id
                       :world-id world-id}
            main-arcs (vec
                        (repeat 3
                                (merge base-meta
                                       {:start start :end end
                                        :ttl main-arc-life :max-ttl main-arc-life
                                        :is-aoe? false})))
            aoe-arcs (->> aoe-points
                          (keep (fn [pt]
                                  (when (map? pt)
                                    (let [life (+ 15 (rand-int 11))]
                                      (merge base-meta
                                             {:start end :end pt
                                              :ttl life :max-ttl life
                                              :is-aoe? true})))))
                          vec)]
        (swap! arcs update owner-key* (fnil into []) (into main-arcs aoe-arcs)))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:em.arc_strong" :volume 0.6 :pitch 1.0}))))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! arcs
         (fn [owners]
           (->> owners
                (keep (fn [[owner-key xs]]
                        (let [live-arcs (->> xs
                                             (map #(update % :ttl dec))
                                             (filter #(pos? (long (:ttl %))))
                                             vec)]
                          (when (seq live-arcs)
                            [owner-key live-arcs]))))
                (into {})))))

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
  (let [current-arcs (all-arcs)
        plan (mapcat #(arc-ops camera-pos %) current-arcs)]
    (when (seq plan)
      {:ops (vec plan)})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :thunder-bolt-strike
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channel! :thunder-bolt/fx-perform
    (fn [ctx-id channel payload]
      (level-effects/enqueue-level-effect! :thunder-bolt-strike payload
                                           {:ctx-id ctx-id
                                            :channel channel})))
  nil)
