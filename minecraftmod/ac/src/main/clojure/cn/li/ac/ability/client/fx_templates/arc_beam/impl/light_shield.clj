(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.light-shield
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private loop-sound-id (modid/namespaced-path "md.shield_loop"))

(defn- loop-sound-key [ctx-id] (str "light-shield/" ctx-id))

(defn- start-loop-sound!
  "c_spawn: FollowEntitySound(player, \"md.shield_loop\").setLoop() — a loop
  sample riding the caster for as long as the shield is up, at MovingSound's
  default volume (LightShield never calls setVolume)."
  [ctx-id source-player-id]
  (client-bridge/run-client-effect!
    :mcmod/start-loop-sound-at-player
    {:key (loop-sound-key ctx-id)
     :sound-id loop-sound-id
     :owner-uuid (str source-player-id)
     :volume 1.0
     :pitch 1.0}))

(defn- stop-loop-sound!
  "c_end: loopSound.stop()."
  [ctx-id]
  (client-bridge/run-client-effect!
    :mcmod/stop-loop-sound
    {:key (loop-sound-key ctx-id)}))

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including its two unreachable branches. combat_content.clj's :light-shield
;; skill never sends a :start :vfx step at all (:start only patches
;; resources/session and fires a :domain-event); its :pulse phase sends
;; :event :active (not :tick), with :params {:radius 3.0} only -- no :pos,
;; no :source-player-id, no :world-id. So :start/:tick have never matched a
;; real event (both fall through to the trailing `store*` no-op default),
;; :active itself falls through that same default today, and even if :active
;; were wired up, tick-state!'s particle logic below still could never fire
;; because payload never carries :pos -- the :pos-carrying :tick channel
;; below (light_shield_fx.clj's :channels) was the old, now-dead,
;; channel/topic transport (see docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section P1.3), never replaced with an equivalent on the live combat-core
;; :vfx signal path. This is a pre-existing gap (see task C-2.3's
;; "conservative implementation, touch-damage only" scope note -- the
;; shield's visual/audio were never fully ported) that this migration does
;; not attempt to fix: doing so would need a live position source this
;; pipeline doesn't have today, which is a real design task, not a
;; lifecycle-flattening one. Flattened structurally only; behavior (or lack
;; thereof) is unchanged.
(defn- enqueue-state!
  [store ctx-id channel _owner-key payload]
  (let [store* (or store {})
        {:keys [mode source-player-id world-id pos]} (or payload {})
        base-meta {:queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        ;; c_spawn: md.shield_startup at 0.5. md.shield_on/md.shield_off are
        ;; port-invented ids the original has no counterpart for.
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.shield_startup") :volume 0.5 :pitch 1.0})
        (start-loop-sound! ctx-id source-player-id)
        (merge store* base-meta {:active? true :ticks 0 :phase :startup}))
      :tick
      ;; Only refreshes an already-active shield -- a late tick must not
      ;; resurrect one that already ended.
      (if (:active? store*)
        (assoc store* :pos pos)
        store*)
      :end
      (do
        (stop-loop-sound! ctx-id)
        (assoc store* :active? false))
      store*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Only reachable while :active?, matching the pre-migration
   per-owner active flag."
  [store]
  (let [state* (or store {})]
    (when (:active? state*)
      ;; Upstream c_update: 30%/tick md particles at lookingPos(player, 1) --
      ;; one block ahead of the eyes -- with ranged(-s, s) jitter on each
      ;; axis. Never actually fires today: :pos is only ever set by the
      ;; :tick branch above, which (per the comment there) never receives a
      ;; real event, so it stays nil and this when-let is always skipped.
      (when-let [pos (:pos state*)]
        (when (< (rand) 0.3)
          (let [s 0.5
                jitter (fn [] (- (rand (* 2.0 s)) s))]
            (client-particles/queue-particle-effect! (:queue-owner state*)
              {:type :particle :particle-type (modid/namespaced-path "md_particle")
               :x (+ (double (:x pos)) (jitter))
               :y (+ (double (:y pos)) (jitter))
               :z (+ (double (:z pos)) (jitter))
               :count 1 :speed 1.0
               :offset-x (- (rand 0.04) 0.02)
               :offset-y (- (rand 0.06) 0.01)
               :offset-z (- (rand 0.04) 0.02)}))))
      (assoc state* :ticks (inc (long (or (:ticks state*) 0)))))))

(defn- destroy-fx!
  "vfx-core's :destroy hook: release the loop sound on any teardown path
   that isn't the normal :end case above (which already stopped it) --
   explicit :destroy signal, clear-owner!/clear-world!, or :update itself
   returning nil."
  [state]
  (when (:active? state)
    (stop-loop-sound! (:ctx-id state))))

(defn- build-plan
  [camera-pos _hand-center-pos tick]
  ;; The shield visual is the spawned entity_md_shield (spinning-shield
  ;; render profile — upstream EntityMdShield + RenderMdShield); the old
  ;; fx-level ring/spokes/glow at the hand rendered a stray light disc by
  ;; the head that read as a useless overhead ray. Particles above.
  (let [cam-v (when (map? camera-pos) (vec3/map->v3 camera-pos))
        {:keys [active? pos ticks]} (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :light-shield)
        texture (modid/namespaced-path "textures/effects/mdshield.png")]
    (when (and active? pos cam-v)
      (let [center (vec3/map->v3 pos)
            right (ru/camera-facing-right-axis center cam-v)
            up (ru/billboard-up-axis center cam-v right)
            pulse (+ 1.0 (* 0.08 (Math/sin (* 0.18 (double (or ticks tick 0))))))
            side (vec3/v* right (* 0.95 pulse))
            top (vec3/v* up (* 1.15 pulse))]
        {:ops [(ru/quad-op texture
                            (vec3/v+ (vec3/v- center side) top)
                            (vec3/v+ (vec3/v+ center side) top)
                            (vec3/v- (vec3/v+ center side) top)
                            (vec3/v- (vec3/v- center side) top)
                            {:r 170 :g 245 :b 255 :a 190})]}))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:light-shield :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:light-shield :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:light-shield :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :light-shield
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-destroy! :light-shield [_ state] (destroy-fx! state))
;; No effect-clear-owner! override anymore -- superseded by :destroy-fn
;; above (build-spec wires it unconditionally via dispatch-destroy!), which
;; vfx-core's real destroy!/clear-owner! now reach correctly per instance.
;; :clear-owner-fn itself has no live caller for any effect (see the
;; vfx-core destroy! commit).
