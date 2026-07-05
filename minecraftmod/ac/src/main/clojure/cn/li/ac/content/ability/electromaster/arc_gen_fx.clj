(ns cn.li.ac.content.ability.electromaster.arc-gen-fx
  "Client FX for Arc-Gen: short electric arc beam and weak arc sound."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.arc-patterns :as arc]))

(def ^:private sound-id "my_mod:em.arc_weak")
(def ^:private arc-life 10)
(def ^:private arc-gen-effect-id :arc-gen)

(defn default-arc-gen-fx-runtime-state
  []
  {:arcs {}})

(defn arc-gen-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot arc-gen-effect-id)
      (default-arc-gen-fx-runtime-state)))

(defn reset-arc-gen-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    arc-gen-effect-id
    (default-arc-gen-fx-runtime-state))
  nil)

(defn clear-arc-gen-owner!
  [owner-key]
  (level-effects/update-effect-state!
    arc-gen-effect-id
    (fn [store]
      (let [store* (if (contains? (or store {}) :arcs)
                     (or store (default-arc-gen-fx-runtime-state))
                     (default-arc-gen-fx-runtime-state))]
        (update store* :arcs dissoc owner-key))))
  nil)

(defn- all-arcs []
  (mapcat val (:arcs (arc-gen-fx-snapshot))))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :arcs)
                 (or store (default-arc-gen-fx-runtime-state))
                 (default-arc-gen-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-type source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :perform
      (when (and (map? start) (map? end))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0})
        (update-in store* [:arcs owner-key*] (fnil conj [])
                   (merge base-meta
                          {:start start
                           :end end
                           :hit-type hit-type
                           :ttl arc-life
                           :max-ttl arc-life})))

      :end
      (update store* :arcs dissoc owner-key*)

      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :arcs)
                 (or store (default-arc-gen-fx-runtime-state))
                 (default-arc-gen-fx-runtime-state))]
    (update store* :arcs
      (fn [by-owner]
        (into {}
              (keep (fn [[owner-key items]]
                      (let [live (->> items
                                      (map #(update % :ttl dec))
                                      (filter #(pos? (long (:ttl %))))
                                      vec)]
                        (when (seq live)
                          [owner-key live]))))
              by-owner)))))

(defn- arc-ops [cam-pos {:keys [start end ttl max-ttl]}]
  (ru/zigzag-arc-ops cam-pos start end
                     {:arc-pattern :weak
                      :life-ratio (/ (double ttl) (double (max 1 max-ttl)))}))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (arc/tick-wiggle-phase!)
  (let [items (all-arcs)
        ops (mapcat #(arc-ops camera-pos %) items)]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init! []
  (fx-spec/register!
    {:id arc-gen-effect-id
     :level {:initial-state (default-arc-gen-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:perform {:topic :arc-gen/fx-perform :mode :perform
                         :level-payload (fn [_ _ p]
                                          {:start (:start p)
                                           :end (:end p)
                                           :hit-type (:hit-type p)})}}})
  nil)
