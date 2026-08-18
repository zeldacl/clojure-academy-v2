(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.directed-shock
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.client.effect-controller :as vfx-hand]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private prepare-duration-ms 150.0)
(def ^:private punch-duration-ms 300.0)

(defn- now-ms [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Transform curves (timestamp-based animation)
;; ---------------------------------------------------------------------------

(defn- prepare-transform [progress]
  {:tx (vfx-hand/sample-curve [[0.0 0.0] [1.0 -0.02]] progress)
   :ty (vfx-hand/sample-curve [[0.0 0.0] [0.5 0.2] [1.0 0.4]] progress)
   :tz (vfx-hand/sample-curve [[0.0 0.0] [1.0 -0.05]] progress)
   :rot-x (vfx-hand/sample-curve [[0.0 0.0] [1.0 -20.0]] progress)
   :rot-y 0.0
   :rot-z 0.0})

(defn- punch-transform [progress]
  {:tx (vfx-hand/sample-curve [[0.0 -0.04] [0.5 -0.04] [1.0 0.0]] progress)
   :ty (vfx-hand/sample-curve [[0.0 0.8] [0.5 0.75] [1.0 0.0]] progress)
   :tz (vfx-hand/sample-curve [[0.0 0.0] [0.3 -0.4] [1.0 0.0]] progress)
   :rot-x (vfx-hand/sample-curve [[0.0 -40.0] [0.5 -45.0] [1.0 0.0]] progress)
   :rot-y (vfx-hand/sample-curve [[0.0 0.0] [0.3 10.0] [1.0 0.0]] progress)
   :rot-z 0.0})

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration.
;; combat_content.clj's :directed-shock skill sends exactly ONE :vfx step,
;; ever: :event :perform from its :release phase, with
;; :params {:charge-min-ticks 6} -- no :start/:end. Unlike most Batch 2/3
;; effects, :perform here DOES match a real case branch (:stage :punch), so
;; the punch hand-animation genuinely plays; only the windup (:start's
;; :stage :prepare, which would show before the punch) never fires. The
;; punch SOUND does not play either way -- it was queued by
;; directed_shock_fx.clj's :immediate :channels handler, which has been
;; dead code since :channels itself was deleted as dead infrastructure (see
;; E section P1.3; fx_spec.clj's register! tolerates and ignores :channels
;; entirely now).
(defn- enqueue-state! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [mode performed? source-player-id world-id]} payload
        base-meta {:ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (merge state* base-meta {:stage :prepare :started-at (now-ms)})

      ;; The punch sound itself is queued by directed-shock-fx.clj's
      ;; :immediate channel handler instead of here — see the migration
      ;; comment above for why that no longer fires at all.
      :perform
      (merge state* base-meta {:stage :punch :started-at (now-ms)})

      :end
      (if performed? state* {})

      state*)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Only the finished :punch stage naturally ends the
   instance (matching the pre-migration per-owner removal exactly); a
   :prepare stage (or no stage at all) persists, same as before."
  [state]
  (let [state* (or state {})]
    (when-not (and (= :punch (:stage state*))
                   (>= (- (now-ms) (long (or (:started-at state*) 0))) punch-duration-ms))
      state*)))

;; ---------------------------------------------------------------------------
;; Transform
;; ---------------------------------------------------------------------------

(defn- transform []
  (when-let [uuid (client-bridge/local-player-uuid)]
    (when-let [{:keys [stage started-at]} (vfx-hand/instance-for-owner :directed-shock uuid)]
      (when stage
        (let [elapsed (- (now-ms) (long started-at))]
          (case stage
            :prepare
            (prepare-transform (min 1.0 (/ elapsed prepare-duration-ms)))
            :punch
            (let [progress (/ elapsed punch-duration-ms)]
              (when (< progress 1.0)
                (punch-transform progress)))
            nil))))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:directed-shock :hand] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:directed-shock :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:directed-shock :hand] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-transform-fn :directed-shock [_effect-id] (transform))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
;; The finished-punch-stage removal above already handles the only
;; teardown this file ever needed; the manual clear-owner! call this
;; sampling function used to make once progress hit 1.0 was redundant with
;; that and, like every other :clear-owner-fn caller, dead anyway.
