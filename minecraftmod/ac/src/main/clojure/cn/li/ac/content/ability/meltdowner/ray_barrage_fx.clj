(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private ray-barrage-effect-id :ray-barrage)

(defn- preray-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.ray_barrage") :volume 0.35 :pitch 0.95})
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_barrage_ray_pre"}))

(defn- barrage-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.ray_barrage") :volume 0.45 :pitch 1.1})
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_md_ray_barrage"}))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :ray-barrage
     :initial-state (fn [] {:beam-queue {}})
     :channels {:preray {:topic :ray-barrage/fx-preray :targets [:immediate]
                         :immediate-fn preray-sound!}
                :barrage {:topic :ray-barrage/fx-barrage :targets [:immediate]
                          :immediate-fn barrage-sound!}
                :beam {:topic :ray-barrage/fx-beam
                       :handler (fn [ctx-id channel payload]
                                  (let [origin (or (:start payload) (:origin payload))
                                        beam-end (or (:end payload) (:beam-end payload))]
                                    (when (and (map? origin) (map? beam-end))
                                      (level-effects/enqueue-level-effect! ray-barrage-effect-id ctx-id channel
                                        (merge (fx-spec/select-meta payload)
                                               {:from-x (:x origin) :from-y (:y origin) :from-z (:z origin)
                                                :to-x (:x beam-end) :to-y (:y beam-end) :to-z (:z beam-end)})))))}}}))

(arc-beam/def-arc-beam-fx :ray-barrage)
