(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.light-shield
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store {:effect-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.shield_on") :volume 0.7 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :phase :startup})))
      :end
      (update store* :effect-state dissoc owner-key*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store {:effect-state {}})]
    (update store* :effect-state
      (fn [states]
        (reduce-kv
          (fn [acc owner-key st]
            (if-not (:active? st)
              acc
              (let [ticks (inc (long (or (:ticks st) 0)))]
                ;; Upstream c_update: 30%/tick md particles near the player
                ;; (MdParticleFactory — soft dots, not spark lines).
                (when (< (rand) 0.3)
                  (let [s 0.5]
                    (client-particles/queue-particle-effect! (:queue-owner st)
                      {:type :particle :particle-type (modid/namespaced-path "md_particle")
                       :x (+ (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :y (+ 1.0 (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :z (+ (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :count 1 :speed 0.08
                       :offset-x 0.02 :offset-y 0.02 :offset-z 0.02
                       :motion-x (- (rand 0.04) 0.02)
                       :motion-y (- (rand 0.04) 0.02)
                       :motion-z (- (rand 0.04) 0.02)})))
                (assoc acc owner-key (assoc st :ticks ticks)))))
          {}
          states)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  ;; The shield visual is the spawned entity_md_shield (spinning-shield
  ;; render profile — upstream EntityMdShield + RenderMdShield); the old
  ;; fx-level ring/spokes/glow at the hand rendered a stray light disc by
  ;; the head that read as a useless overhead ray. Particles above.
  nil)

(defn- shield-end-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.shield_loop") :volume 0.35 :pitch 0.95}))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:light-shield :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:light-shield :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:light-shield :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :light-shield
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :light-shield [_ store owner-key]
  (update store :effect-state dissoc owner-key))
