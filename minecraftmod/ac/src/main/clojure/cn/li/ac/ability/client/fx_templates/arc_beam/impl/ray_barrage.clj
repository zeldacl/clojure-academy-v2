(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.ray-barrage
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]))

(defn- all-beams
  []
  (mapcat val (:beam-queue (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :ray-barrage))))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store {:beam-queue {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}
        beam (merge base-meta (or payload {}) {:ttl 12})]
    (update store* :beam-queue
      (fn [by-owner]
        (let [q (vec (get by-owner owner-key*))
              q* (if (> (count q) 10)
                   (subvec q (- (count q) 10))
                   q)]
          (assoc by-owner owner-key* (conj q* beam)))))))

(defn- tick-state!
  [store]
  (let [store* (or store {:beam-queue {}})]
    (update store* :beam-queue
      (fn [by-owner]
        (into {}
              (keep (fn [[owner-key q]]
                      (let [live (->> q
                                      (map (fn [b] (update b :ttl dec)))
                                      (filter (fn [b] (pos? (:ttl b))))
                                      vec)]
                        (when (seq live)
                          [owner-key live]))))
              by-owner)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (when-let [beams (seq (all-beams))]
    {:ops (mapcat
            (fn [beam]
              (let [{:keys [from-x from-y from-z to-x to-y to-z ttl]} beam
                    alpha (int (* 180 (/ (double ttl) 12.0)))
                    col {:r 255 :g 100 :b 50 :a alpha}]
                [(ru/line-op (vec3/v3 from-x from-y from-z)
                             (vec3/v3 to-x to-y to-z)
                             col)]))
            beams)}))

(defn- preray-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.ray_barrage") :volume 0.35 :pitch 0.95})
  ;; Spawn EntityBarrageRayPre equivalent
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_barrage_ray_pre"}))

(defn- barrage-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.ray_barrage") :volume 0.45 :pitch 1.1})
  ;; Spawn EntityMdRayBarrage equivalent
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_md_ray_barrage"}))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:ray-barrage :level] [_ _] {:beam-queue {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:ray-barrage :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:ray-barrage :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :ray-barrage [store owner-key]
  (update store :beam-queue dissoc owner-key))
