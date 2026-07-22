(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-fx
  "Client FX for ScatterBomb: ball spawn + scatter beam flashes."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private scatter-bomb-effect-id :scatter-bomb)

(defn default-scatter-bomb-fx-runtime-state
  []
  {:effect-state {}})

(defn scatter-bomb-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot scatter-bomb-effect-id)
      (default-scatter-bomb-fx-runtime-state)))

(defn reset-scatter-bomb-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    scatter-bomb-effect-id
    (default-scatter-bomb-fx-runtime-state))
  nil)

(defn clear-scatter-bomb-owner!
  [owner-key]
  (level-effects/update-effect-state!
    scatter-bomb-effect-id
    (fn [store]
      (update (or store (default-scatter-bomb-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-scatter-bomb-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z count start end source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.sb_charge") :volume 0.5 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :balls 0})))
      :ball
      (do
        (client-particles/queue-current-particle-effect!
          {:type :particle :particle-type :electric-spark
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))
           :count 4 :speed 0.1
           :offset-x 0.3 :offset-y 0.3 :offset-z 0.3})
        (update-in store* [:effect-state owner-key*]
          (fn [st]
            (assoc (merge base-meta (or st {:active? true :ticks 0}))
                   :owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id
                   :balls (int (or count 0))))))
      :beam
      (do
        (when (and start end)
          (client-particles/queue-current-particle-effect!
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 4 :speed 0.15
             :offset-x 0.4 :offset-y 0.4 :offset-z 0.4})
          ;; Spawn EntityMdRaySmall equivalent (matching original SBNetDelegate)
          (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
            {:effect-id "entity_md_ray_small"}))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.eb_explode") :volume 0.4 :pitch 1.2})
        store*)
      :end
      (update store* :effect-state dissoc owner-key*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-scatter-bomb-fx-runtime-state))]
    (update store* :effect-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (when (:active? st)
                        [owner-key (assoc st :ticks (inc (long (or (:ticks st) 0))))])))
              states)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick & _more]
  nil)

(defn init!
  []
  (fx-spec/register!
    {:id scatter-bomb-effect-id
     :level {:initial-state (default-scatter-bomb-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :scatter-bomb/fx-start :mode :start}
                :ball {:topic :scatter-bomb/fx-ball :mode :ball
                       :level-payload (fn [_ _ p]
                                        {:x (:x p) :y (:y p) :z (:z p) :count (:count p)})}
                :beam {:topic :scatter-bomb/fx-beam :mode :beam
                       :level-payload (fn [_ _ p]
                                        {:start (:start p) :end (:end p)})}
                :end {:topic :scatter-bomb/fx-end :mode :end}}})
  nil)
