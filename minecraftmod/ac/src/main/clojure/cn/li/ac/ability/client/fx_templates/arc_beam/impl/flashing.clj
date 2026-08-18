(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.flashing
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.client.effect-controller :as vfx-hand]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark :as tp-mark]
            [cn.li.ac.content.ability.teleporter.flashing-dest :as fdest]))

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :fx-state owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including the fact that none of its branches match a real event today --
;; same finding as mark_teleport.clj/penetrate_teleport.clj (shared trigger
;; shape). combat_content.clj's :flashing skill sends exactly ONE :vfx step,
;; ever: :event :release from its :release phase, with
;; :params {:blink-distance ...} -- no :state-start/:preview-start/
;; :preview-update/:preview-end/:perform/:state-end, and none of the
;; :to-x/:to-y/:to-z/:direction/:distance/:from-x/:from-y/:from-z fields
;; this file's branches read. :release itself falls through to the trailing
;; `state*` no-op default. In production today the preview marker never
;; renders and the portal-particle burst never fires -- migrated
;; structurally only.
(defn- enqueue-state! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [source-player-id world-id]} payload
        base-meta {:queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      ;; The visible marking is rendered by build-plan at :preview (upstream
      ;; EntityTPMarking at getDest). The scripted entity_tp_marking is NOT
      ;; spawned — it follows the player and renders at their head instead.
      :state-start
      (merge state* base-meta {:preview nil :burst (or (:burst state*) [])})
      :preview-start
      (assoc (merge base-meta (or state* {:burst []}))
             :direction (:direction payload)
             :dist (double (or (:distance payload) 0.0))
             :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})
      :preview-update
      (assoc (merge base-meta (or state* {:burst []}))
             :direction (:direction payload)
             :dist (double (or (:distance payload) 0.0))
             :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})
      :preview-end
      (assoc (merge base-meta (or state* {:burst []})) :preview nil)
      :perform
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp_flashing") :volume 1.0 :pitch 1.0})
        (update (merge base-meta (or state* {:preview nil :burst []})) :burst (fnil conj [])
                {:ttl 8 :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
                 :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}}))
      :state-end
      (assoc state* :preview nil :burst [])
      state*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Stay alive while a preview is set or a burst is still
   animating, mirroring vec_deviation's tick-state!."
  [state]
  (let [state* (or state {})
        burst (:burst state*)]
    (doseq [b burst]
      (let [{fx :x fy :y fz :z} (:from b) {tx :x ty :y tz :z} (:to b)]
        (when (pos? (long (or (:ttl b) 0)))
          (client-particles/queue-particle-effect! (:queue-owner state*)
            {:type :particle :particle-type :portal :x (double fx) :y (double fy) :z (double fz)
             :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})
          (client-particles/queue-particle-effect! (:queue-owner state*)
            {:type :particle :particle-type :portal :x (double tx) :y (double ty) :z (double tz)
             :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35}))))
    (let [next-burst (store-tick/tick-ttl-vec burst)]
      (when (or (:preview state*) (seq next-burst))
        (assoc state* :burst next-burst)))))

;; Upstream Flashing spawns an EntityTPMarking (the same humanoid as
;; penetrate/mark teleport) at getDest and moves it there every tick while a
;; movement key is held. The scripted "entity_tp_marking" marker has no
;; renderer, so the visible marking is drawn here with the shared tp_mark
;; humanoid (white tint — upstream MarkRender's default).
(def ^:private color-marking {:r 255 :g 255 :b 255 :a 255})

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

(defn- live-preview
  "localTick re-runs getDest(performingKey) against the client's own world
  every tick, so the marking follows the view with no round trip. The blink
  distance comes from the server (it is lerped off skill exp); the direction
  key, the trace and the six-face table are re-solved here through the same
  namespace serverPerform calls."
  [st hand-center-pos]
  (let [raycast (:raycast-combined-excluding-from hand-center-pos)
        solid? (:block-solid-at? hand-center-pos)
        dist (double (or (:dist st) 0.0))
        eye-y (:player-eye-y hand-center-pos)
        look (look-vec (:player-yaw-rad hand-center-pos)
                       (:player-pitch-rad hand-center-pos))]
    (or (when (and raycast look eye-y (:direction st) (pos? dist))
          (let [resolved (fdest/destination
                           {:x (:player-x hand-center-pos)
                            :y (:player-y hand-center-pos)
                            :z (:player-z hand-center-pos)
                            :eye-y eye-y
                            :look-vec look
                            :direction (:direction st)
                            :dist dist
                            :raycast raycast
                            :head-blocked? (when solid?
                                             (fn [x y z]
                                               (solid? (int x) (int (+ (double y) 1.0)) (int z))))})]
            {:x (:to-x resolved) :y (:to-y resolved) :z (:to-z resolved)}))
        (:preview st))))

(defn- build-plan [_camera-pos hand-center-pos tick]
  (let [st (vfx-level/effect-state-snapshot :flashing)
        yaw-rad (:player-yaw-rad hand-center-pos)
        ops (when (:preview st)
              (when-let [preview (live-preview st hand-center-pos)]
                (tp-mark/humanoid-ops yaw-rad preview (long tick) color-marking)))]
    (when (seq ops)
      {:ops (vec ops)})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :flashing
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:flashing :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:flashing :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:flashing :level] [_ _ store] (tick-state! store))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
