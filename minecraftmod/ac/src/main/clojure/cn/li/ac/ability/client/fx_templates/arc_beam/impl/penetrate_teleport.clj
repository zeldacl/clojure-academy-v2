(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.penetrate-teleport
  "Upstream PenetrateTeleport alignment (PTContext + EntityTPMarking +
  MarkRender).

  The aim marker is the same EntityTPMarking as MarkTeleport: a tp_mark
  7-frame humanoid. Upstream l_updateMark positions it at
  mark.setPosition(dest.x, dest.y + player.eyeHeight, dest.z) — the humanoid
  floats at eye level above the destination — and sets mark.available =
  dest.available. MarkRender tints the model: white when available, red
  glColor4d(1, 0.2, 0.2, 1) when the destination is still inside a wall.
  EntityTPMarking.onUpdate spawns green TPParticleFactory particles only
  while available (rand.nextDouble() < 0.4). On key-up upstream plays
  tp.tp (local) before server validation — no portal burst."
  (:require [cn.li.ac.ability.client.effects.billboard-particles :as bp]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark :as tp-mark]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.config.modid :as modid]))

(def ^:private eye-height
  "Upstream l_updateMark adds player.eyeHeight — vanilla EntityPlayer eye
  height 1.62 — so the humanoid's feet hover at the player's eye level above
  the destination."
  1.62)

(def ^:private color-available {:r 255 :g 255 :b 255 :a 255})

(def ^:private color-unavailable
  "Upstream MarkRender: glColor4d(1, 0.2, 0.2, 1) when !mark.available."
  {:r 255 :g 51 :b 51 :a 255})

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}
        target-state {:active? true :ticks 0
                      :target {:x (double (or (:x payload) 0.0))
                               :y (+ (double (or (:y payload) 0.0)) eye-height)
                               :z (double (or (:z payload) 0.0))}
                      :available? (boolean (:available? payload))
                      :distance (double (or (:distance payload) 0.0))
                      :ambient-particles []}]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta target-state))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                ;; Refresh target/available/distance only — keep :ticks and
                ;; :ambient-particles across updates (the mark ticks every
                ;; frame; a reset would freeze the tp_mark frame sequence).
                (merge base-meta (or st target-state)
                       {:target (:target target-state)
                        :available? (boolean (:available? payload))
                        :distance (double (or (:distance payload) 0.0))})))
      :perform
      (do
        ;; Upstream l_onKeyUp: ACSounds.playClient(player, "tp.tp", ...) —
        ;; local release sound before server validation, no particles.
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0})
        ;; Upstream c_endEffect kills the mark on MSG_TERMINATED.
        (update state* :fx-state dissoc owner-key*))
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (reduce-kv (fn [acc k st]
                           (if (:active? st)
                             ;; Upstream EntityTPMarking.onUpdate: particles
                             ;; only while available.
                             (assoc acc k (tp-mark/tick-marker! st (fn [st] (:available? st))))
                             acc))
                         {}
                         states)))))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [store (level-effects/effect-state-snapshot :penetrate-teleport)
        cam (rv3/map->v3 camera-pos)
        ops (vec (mapcat (fn [st]
                           (when (:active? st)
                             (let [color (if (:available? st) color-available color-unavailable)]
                               (into (tp-mark/humanoid-ops cam (:target st) (:ticks st) color)
                                     (bp/particle-ops cam (:ambient-particles st))))))
                         (vals (:fx-state store))))]
    (when (seq ops)
      {:ops ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:penetrate-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:penetrate-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:penetrate-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :penetrate-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :penetrate-teleport [_ store owner-key]
  (update store :fx-state dissoc owner-key))
