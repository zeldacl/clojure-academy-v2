(ns cn.li.ac.content.ability.electromaster.body-intensify-fx
  "Client FX for body-intensify."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private activate-sound-id (modid/namespaced-path "em.intensify_activate"))
(def ^:private loop-sound-id (modid/namespaced-path "em.intensify_loop"))
(defn- loop-key [ctx-id]
  (str "body-intensify/" ctx-id))

(defn- presentation-owner [ctx-id payload]
  (str "body-intensify/" (or (:source-player-id payload) "unknown") "/" ctx-id))

(defn- on-fx-start
  [ctx-id _channel payload]
  (client-bridge/call-adapter
    :presentation-spawn-effect!
    :academy/body-intensify-charge
    (presentation-owner ctx-id payload)
    {:count 1 :material-id 0}
    (System/currentTimeMillis))
  ;; FollowEntitySound upstream is a real ambient loop attached to the caster.
  (client-bridge/run-client-effect!
   :mcmod/start-loop-sound-at-player
   {:key (loop-key ctx-id)
    :sound-id loop-sound-id
    :owner-uuid (:source-player-id payload)
    :volume 1.0
    :pitch 1.0}))

(defn- on-fx-end
  [ctx-id _channel payload]
  (client-bridge/call-adapter
    :presentation-clear-effect-owner!
    (presentation-owner ctx-id payload))
  ;; Stop any charging loop started by fx-start before handling release.
  (client-bridge/run-client-effect!
   :mcmod/stop-loop-sound
   {:key (loop-key ctx-id)})
  ;; Upstream c_endEffect: isLocal → hud.startBlend(performed). No-op for the
  ;; fanned-out copies about other players' releases.
  (reactive-hud/start-charging-blend! (:source-player-id payload)
                                      (boolean (:performed? payload)))
  (when (:performed? payload)
    (let [{:keys [x y z]} (:caster-pos payload)]
      (client-sounds/queue-current-sound-effect!
       (cond-> {:sound-id activate-sound-id
                :source :ambient
                :volume 0.5
                :pitch 1.0}
         (number? x) (assoc :x (double x))
         (number? y) (assoc :y (double y))
         (number? z) (assoc :z (double z)))))
    ;; The performed burst is now a typed Effect Instance.  Position is data
    ;; in the instance params; the version backend decides how to bill-board
    ;; or batch the particle command for the active world.
    (client-bridge/call-adapter
      :presentation-spawn-effect!
      :academy/body-intensify-burst
      (presentation-owner ctx-id payload)
      (select-keys (:caster-pos payload) [:x :y :z])
      (System/currentTimeMillis))))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :body-intensify
     :runtime :none
     :channels [{:topic :body-intensify/fx-start
                 :targets [:immediate]
                 :immediate-fn on-fx-start}
                {:topic :body-intensify/fx-end
                 :targets [:immediate]
                 :immediate-fn on-fx-end}]}))

(arc-beam/def-arc-beam-fx :body-intensify)
