(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.flashing
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark :as tp-mark]))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      ;; The visible marking is rendered by build-plan at :preview (upstream
      ;; EntityTPMarking at getDest). The scripted entity_tp_marking is NOT
      ;; spawned — it follows the player and renders at their head instead.
      :state-start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:preview nil :burst []}))
      :preview-start
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:burst []}))
                       :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:burst []}))
                       :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-end
      (update state* :fx-state update owner-key* (fn [st] (assoc (merge base-meta (or st {:burst []})) :preview nil)))
      :perform
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp_flashing") :volume 1.0 :pitch 1.0})
        (update state* :fx-state update owner-key*
                (fn [st] (update (merge base-meta (or st {:preview nil :burst []})) :burst (fnil conj [])
                                 {:ttl 8 :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
                                  :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}}))))
      :state-end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (persistent!
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
                  (assoc! acc owner-key
                          (assoc st :burst (store-tick/tick-ttl-vec (:burst st)))))
                (transient {})
                states))))))

(defn- preview-to-payload [_ctx-id _channel p] {:to-x (:to-x p) :to-y (:to-y p) :to-z (:to-z p)})

;; Upstream Flashing spawns an EntityTPMarking (the same humanoid as
;; penetrate/mark teleport) at getDest and moves it there every tick while a
;; movement key is held. The scripted "entity_tp_marking" marker has no
;; renderer, so the visible marking is drawn here with the shared tp_mark
;; humanoid (white tint — upstream MarkRender's default).
(def ^:private color-marking {:r 255 :g 255 :b 255 :a 255})

(defn- build-plan [camera-pos _hand-center-pos tick]
  (let [cam (rv3/map->v3 camera-pos)
        ops (vec
             (mapcat (fn [st]
                       (when-let [preview (:preview st)]
                         (tp-mark/humanoid-ops cam preview (long tick) color-marking)))
                     (vals (:fx-state (level-effects/effect-state-snapshot :flashing)))))]
    (when (seq ops)
      {:ops ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :flashing
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:flashing :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:flashing :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:flashing :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :flashing [_ store owner-key]
  (update store :fx-state dissoc owner-key))
