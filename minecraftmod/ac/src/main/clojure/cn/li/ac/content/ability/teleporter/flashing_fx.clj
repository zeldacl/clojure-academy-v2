(ns cn.li.ac.content.ability.teleporter.flashing-fx
  "Client FX for Flashing: EntityTPMarking preview + perform burst.
  Matching original AcademyCraft: EntityTPMarking at destination, portal burst on teleport."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private flashing-effect-id :flashing)

(defn default-flashing-fx-runtime-state [] {:fx-state {}})
(defn flashing-fx-snapshot []
  (or (level-effects/effect-state-snapshot flashing-effect-id) (default-flashing-fx-runtime-state)))
(defn reset-flashing-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test! flashing-effect-id (default-flashing-fx-runtime-state)) nil)
(defn clear-flashing-owner! [owner-key]
  (level-effects/update-effect-state! flashing-effect-id
    (fn [state] (update (or state (default-flashing-fx-runtime-state)) :fx-state dissoc owner-key))) nil)

(defn- spawn-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_tp_marking"}))
(defn- remove-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-flashing-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :state-start
      (do (spawn-tp-marking!) (update state* :fx-state assoc owner-key* (merge base-meta {:preview nil :burst []})))
      :preview-start
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:burst []}))
                              :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-update
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:burst []}))
                              :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-end
      (update state* :fx-state update owner-key* (fn [st] (assoc (merge base-meta (or st {:burst []})) :preview nil)))
      :perform
      (do (remove-tp-marking!)
          (client-sounds/queue-sound-effect! (:queue-owner base-meta)
            {:type :sound :sound-id "my_mod:tp.tp_flashing" :volume 1.0 :pitch (+ 0.95 (rand 0.2))})
          (update state* :fx-state update owner-key*
                  (fn [st] (update (merge base-meta (or st {:preview nil :burst []})) :burst (fnil conj [])
                                   {:ttl 8 :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
                                    :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}}))))
      :state-end
      (do (remove-tp-marking!) (update state* :fx-state dissoc owner-key*))
      state*)))

(defn- tick-state! [state]
  (let [state* (or state (default-flashing-fx-runtime-state))]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (doseq [b (:burst st)]
                    (let [{fx :x fy :y fz :z} (:from b) {tx :x ty :y tz :z} (:to b)]
                      (when (pos? (long (:ttl b)))
                        (client-particles/queue-particle-effect! (:queue-owner st)
                          {:type :particle :particle-type :portal :x (double fx) :y (double fy) :z (double fz)
                           :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})
                        (client-particles/queue-particle-effect! (:queue-owner st)
                          {:type :particle :particle-type :portal :x (double tx) :y (double ty) :z (double tz)
                           :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35}))))
                  (let [burst' (->> (:burst st) (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec)]
                    (assoc acc owner-key (assoc st :burst burst'))))
                {} states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn- preview-to-payload [_ctx-id _channel p] {:to-x (:to-x p) :to-y (:to-y p) :to-z (:to-z p)})

(defn init! []
  (fx-spec/register!
    {:id flashing-effect-id
     :level {:initial-state (default-flashing-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:state-start {:topic :flashing/fx-state-start :mode :state-start}
                :preview-start {:topic :flashing/fx-preview-start :mode :preview-start :level-payload preview-to-payload}
                :preview-update {:topic :flashing/fx-preview-update :mode :preview-update :level-payload preview-to-payload}
                :preview-end {:topic :flashing/fx-preview-end :mode :preview-end}
                :perform {:topic :flashing/fx-perform :mode :perform
                          :level-payload (fn [_c _ch p]
                                           (merge (preview-to-payload _c _ch p)
                                                  {:from-x (:from-x p) :from-y (:from-y p) :from-z (:from-z p)}))}
                :state-end {:topic :flashing/fx-state-end :mode :state-end}}})
  nil)
