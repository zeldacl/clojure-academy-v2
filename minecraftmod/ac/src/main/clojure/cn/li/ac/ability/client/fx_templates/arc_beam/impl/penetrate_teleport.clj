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
            [cn.li.ac.ability.client.fx-templates.arc-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark :as tp-mark]
            [cn.li.ac.content.ability.teleporter.penetrate-dest :as pdest]
            [cn.li.ac.client.effect-controller :as vfx-level]
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

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :fx-state owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including the fact that none of its branches match a real event today --
;; same finding as mark_teleport.clj (shared trigger shape). combat_content
;; .clj's :penetrate-teleport skill sends exactly ONE :vfx step, ever:
;; :event :release from its :release phase, with :params {:max-range 32.0}
;; -- no :start/:update/:perform/:end, and none of the :x/:y/:z/:available?/
;; :distance/:march-distance fields this file's :start/:update branches
;; read. :release itself falls through to the trailing `state*` no-op
;; default. In production today this marker never becomes active and never
;; renders -- migrated structurally only.
(defn- enqueue-state! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [source-player-id world-id]} payload
        base-meta {:queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}
        target-state {:active? true :ticks 0
                      :target {:x (double (or (:x payload) 0.0))
                               :y (+ (double (or (:y payload) 0.0)) eye-height)
                               :z (double (or (:z payload) 0.0))}
                      :available? (boolean (:available? payload))
                      :distance (double (or (:distance payload) 0.0))
                      :march-distance (double (or (:march-distance payload) 0.0))
                      :ambient-particles []}]
    (case (:mode payload)
      :start
      (merge state* base-meta target-state)
      :update
      ;; Refresh target/available/distance only — keep :ticks and
      ;; :ambient-particles across updates (the mark ticks every frame; a
      ;; reset would freeze the tp_mark frame sequence).
      (merge base-meta (or state* target-state)
             {:target (:target target-state)
              :available? (boolean (:available? payload))
              :distance (double (or (:distance payload) 0.0))
              :march-distance (double (or (:march-distance payload) 0.0))})
      :perform
      (do
        ;; Upstream l_onKeyUp: ACSounds.playClient(player, "tp.tp", ...) —
        ;; local release sound before server validation, no particles.
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0})
        ;; Upstream c_endEffect kills the mark on MSG_TERMINATED.
        (assoc state* :active? false))
      :end
      (assoc state* :active? false)
      state*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Only reachable while :active?, matching the pre-migration
   per-owner active flag."
  [state]
  (let [state* (or state {})]
    (when (:active? state*)
      ;; Upstream EntityTPMarking.onUpdate: particles only while available.
      (tp-mark/tick-marker! state* (fn [st] (:available? st))))))

(defn- look-vec
  "Entity.getLookAngle from yaw/pitch, both radians."
  [yaw-rad pitch-rad]
  (when (and yaw-rad pitch-rad)
    (let [yaw (double yaw-rad)
          pitch (double pitch-rad)
          cp (Math/cos pitch)]
      {:x (* -1.0 (Math/sin yaw) cp)
       :y (* -1.0 (Math/sin pitch))
       :z (* (Math/cos yaw) cp)})))

(defn- live-state
  "l_updateMark re-marches getDest against the client's own world every tick,
  setting both the mark's position and its `available` flag -- the flag is what
  paints it red, so a stale one means the marker lies about whether the blink
  will land. The march length comes from the server (getDest clamps it by CP);
  the wall probing is redone here through the same namespace s_execute calls."
  [st hand-center-pos]
  (let [collidable? (:block-collidable-at? hand-center-pos)
        dist (double (or (:march-distance st) 0.0))
        look (look-vec (:player-yaw-rad hand-center-pos)
                       (:player-pitch-rad hand-center-pos))]
    (or (when (and collidable? look (pos? dist))
          (when-let [dest (pdest/destination
                            {:x (:player-x hand-center-pos)
                             :y (:player-y hand-center-pos)
                             :z (:player-z hand-center-pos)
                             :look-vec look
                             :distance dist
                             :collidable? collidable?})]
            {:target {:x (:x dest)
                      ;; l_updateMark: dest.pos.y + player.eyeHeight
                      :y (+ (double (:y dest))
                            (- (double (or (:player-eye-y hand-center-pos)
                                           (+ (double (:player-y hand-center-pos)) eye-height)))
                               (double (:player-y hand-center-pos))))
                      :z (:z dest)}
             :available? (boolean (:available? dest))}))
        {:target (:target st) :available? (:available? st)})))

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [st (vfx-level/effect-state-snapshot :penetrate-teleport)
        cam (rv3/map->v3 camera-pos)
        yaw-rad (:player-yaw-rad hand-center-pos)
        ops (when (:active? st)
              (let [live (live-state st hand-center-pos)
                    color (if (:available? live) color-available color-unavailable)]
                (into (tp-mark/humanoid-ops yaw-rad (:target live) (:ticks st) color)
                      (bp/particle-ops cam (:ambient-particles st)))))]
    (when (seq ops)
      {:ops (vec ops)})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:penetrate-teleport :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:penetrate-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:penetrate-teleport :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :penetrate-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
;; No effect-clear-owner! override anymore -- same reasoning as
;; mark_teleport.clj: no live caller, no side-effecting resource here.
