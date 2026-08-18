(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mark-teleport
  "Upstream MarkTeleport alignment (MTContextC + EntityTPMarking + MarkRender).

  The aim marker is a humanoid at the destination: SimpleModelBiped textured
  with the tp_mark 7-frame effect sequence (frame = ticksExisted / 2.5 % 7),
  hanging from the mark and facing back at the caster. Each tick the mark
  emits a green TPParticleFactory particle 40% of the time (upstream
  rand.nextDouble() < 0.4) around it.

  Marker geometry/particles live in tp-mark (shared with penetrate-teleport,
  which uses the same EntityTPMarking)."
  (:require [cn.li.ac.ability.client.effects.billboard-particles :as bp]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-templates.arc-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark :as tp-mark]
            [cn.li.ac.content.ability.teleporter.mark-teleport-dest :as dest]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.config.modid :as modid]))

(def ^:private mark-color {:r 255 :g 255 :b 255 :a 255})

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including the fact that none of its branches match a real event today.
;; combat_content.clj's :mark-teleport skill (see
;; ability/service/combat_content.clj) sends exactly ONE :vfx step, ever:
;; :event :release from its :release phase, with :params {:max-range 60.0}
;; -- no :start, no :update, no :perform, no :end, and no :target/:dist/
;; :distance/:source-player-id/:world-id anywhere in what actually reaches
;; this file. Every case branch below (:start/:update/:perform/:end) was
;; built for a design this content has never sent; :release itself falls
;; through to the trailing `state*` no-op default, same as every other
;; unmatched mode. In production today this marker never becomes active,
;; never renders, and never plays its :perform sound -- migrated
;; structurally only; not something this migration is scoped to fix (same
;; class of gap as light_shield's, see that file's migration commit).
(defn- enqueue-state! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [mode target distance source-player-id world-id]} payload
        base-meta {:queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id :channel channel
                   :source-player-id source-player-id :world-id world-id}]
    (case mode
      :start
      (merge state* base-meta {:active? true :target target
                               :dist (double (or (:dist payload) 0.0))
                               :distance (double (or distance 0.0))
                               :ticks 0
                               :ambient-particles []})

      :update
      (merge state* base-meta
             {:active? true :target target
              :dist (double (or (:dist payload) 0.0))
              :distance (double (or distance 0.0))})

      :perform
      (do
        ;; Upstream s_execute -> MSG_SOUND -> c_sound:
        ;; ACSounds.playClient(player, "tp.tp", AMBIENT, 0.5) — attached to the
        ;; teleporting player, who is at the destination by then. This channel
        ;; reaches nearby players too, and without coordinates the bang came
        ;; out of their own heads rather than from where someone vanished.
        ;; No burst particles — the mark's ambient ones are the whole effect.
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          (cond-> {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0}
            (map? target) (assoc :x (double (:x target))
                                 :y (double (:y target))
                                 :z (double (:z target)))))
        ;; Upstream l_end kills EntityTPMarking on MSG_TERMINATED.
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
      (tp-mark/tick-marker! state*))))

(defn- look-vec
  "Entity.getLookAngle from yaw/pitch, both radians (xRot is positive looking
  DOWN)."
  [yaw-rad pitch-rad]
  (when (and yaw-rad pitch-rad)
    (let [yaw (double yaw-rad)
          pitch (double pitch-rad)
          cp (Math/cos pitch)]
      {:x (* -1.0 (Math/sin yaw) cp)
       :y (* -1.0 (Math/sin pitch))
       :z (* (Math/cos yaw) cp)})))

(defn- live-target
  "MTContextC.l_update re-runs getDest against the client's own world every
  tick, so the mark tracks the crosshair with no round trip. The server's
  target rides one tick (plus the trip) behind, which on a real server is the
  marker visibly trailing the aim.

  The range for this tick comes from the server (getMaxDist reads CP and exp,
  which it owns); everything downstream of it — the trace, the six-face table,
  the head duck — is re-solved here from the live view through the same
  namespace the server calls. Falls back to the server's own answer whenever
  the view context has no world access, which is what the tests see."
  [mk hand-center-pos]
  (let [raycast (:raycast-from-view hand-center-pos)
        solid? (:block-solid-at? hand-center-pos)
        dist (double (or (:dist mk) 0.0))
        eye-y (:player-eye-y hand-center-pos)
        look (look-vec (:player-yaw-rad hand-center-pos)
                       (:player-pitch-rad hand-center-pos))]
    (or (when (and raycast look eye-y (pos? dist))
          (let [hit (raycast dist true)
                resolved (dest/destination
                           {:hit hit
                            :head-blocked? (when solid?
                                             (fn [x y z]
                                               (solid? (int x) (int (+ (double y) 1.0)) (int z))))
                            :x (:player-x hand-center-pos)
                            :eye-y eye-y
                            :z (:player-z hand-center-pos)
                            :look-vec look
                            :dist dist})]
            {:x (:target-x resolved) :y (:target-y resolved) :z (:target-z resolved)}))
        (:target mk))))

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [mk (vfx-level/effect-state-snapshot :mark-teleport)
        cam (rv3/map->v3 camera-pos)
        yaw-rad (:player-yaw-rad hand-center-pos)
        ops (when (:active? mk)
              (when-let [target (live-target mk hand-center-pos)]
                (into (tp-mark/humanoid-ops yaw-rad target (:ticks mk) mark-color)
                      (bp/particle-ops cam (:ambient-particles mk)))))]
    (when (seq ops)
      {:ops (vec ops)})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mark-teleport :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mark-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mark-teleport :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :mark-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
;; No effect-clear-owner! override anymore -- :clear-owner-fn has no live
;; caller for any effect (see the vfx-core destroy! commit); vfx-core's real
;; destroy!/clear-owner! now reach this instance correctly via instance
;; identity, and there is no side-effecting resource here (no loop sound) to
;; release on teardown, so no :destroy-fn is needed either.
