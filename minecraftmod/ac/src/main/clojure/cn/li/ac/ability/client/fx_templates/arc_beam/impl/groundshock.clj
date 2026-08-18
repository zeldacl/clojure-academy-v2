(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.groundshock
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
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

(def ^:private sound-id (modid/namespaced-path "vecmanip.groundshock"))

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, with
;; the descriptor's own {:level .. :hand ..} two-track split unchanged (that
;; was never an owner-map -- see effect_controller.clj's descriptor).
;; combat_content.clj's :groundshock skill sends exactly ONE :vfx step,
;; ever: :event :perform from its :release phase, with
;; :params {:charge-min-ticks 5} -- no :affected-blocks (the level track's
;; particle burst below has always been dead: this skill's :world-effect
;; step computes the real broken/affected blocks server-side but that
;; result never flows into the :vfx step's params), and no :start/:update/
;; :end for either track. The hand track's punch/camera-shake still works
;; today because its :perform branch (below) already self-initializes via
;; plain `update`-on-a-missing-key, the same idiom mag_movement's :update
;; branch uses -- not something this migration needed to fix.

;; ---------------------------------------------------------------------------
;; Level effect: particle burst on perform
;; ---------------------------------------------------------------------------

(defn- level-enqueue!
  "Pure side-effect emitter (sound + block-break particles), never actually
  rendered from -- no effect-build-plan is registered for :groundshock at
  all, arc_beam.clj's :default build-plan needs :arc-opts this effect never
  sets, so this track carries no state worth keeping between calls. Always
  returns nil so :level trivially satisfies descriptor's :transient
  natural-end check (see effect_controller.clj's :update docstring) the
  moment the :hand track below also goes empty."
  [_store _ctx-id _channel _owner-key payload]
  (let [{:keys [mode affected-blocks]} payload]
    (when (= mode :perform)
      (client-sounds/queue-current-sound-effect!
        {:type :sound :sound-id sound-id :volume 2.0 :pitch 1.0})
      (doseq [{:keys [x y z block-id]} affected-blocks]
        (client-particles/queue-current-particle-effect!
          {:type :particle :particle-type :block-crack
           :block-id (or block-id "minecraft:stone")
           :x (+ (double x) 0.5) :y (+ (double y) 1.0) :z (+ (double z) 0.5)
           :count (+ 4 (rand-int 4)) :speed 0.2
           :offset-x 1.0 :offset-y 0.6 :offset-z 1.0})
        (when (< (rand) 0.5)
          (client-particles/queue-current-particle-effect!
            {:type :particle :particle-type :smoke
             :x (+ (double x) 0.5 (- (* (rand) 0.6) 0.3))
             :y (+ (double y) 1.0 (* (rand) 0.2))
             :z (+ (double z) 0.5 (- (* (rand) 0.6) 0.3))
             :count 1 :speed 0.04
             :offset-x 0.04 :offset-y 0.05 :offset-z 0.04}))))
    nil))

(defn- level-tick! [_store] nil)

;; ---------------------------------------------------------------------------
;; Hand effect: first-person camera shake + punch animation
;; ---------------------------------------------------------------------------

(def ^:private perform-step 3.4)
(def ^:private perform-ticks-count 4)

(defn- hand-enqueue-state! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [mode charge-ticks performed? source-player-id world-id]} payload
        base-meta {:queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (merge state* base-meta {:charge-ticks 0 :perform-ticks 0 :active? true})

      :update
      (let [prev (long (or (:charge-ticks state*) 0))
            next-val (long (max 0 (or charge-ticks 0)))]
        (doseq [tick (range (inc prev) (inc next-val))]
          (let [pitch-factor (cond
                     (< tick 4) (/ tick 4.0)
                     (<= tick 20) 1.0
                     (<= tick 25) (- 1.0 (/ (- tick 20) 5.0))
                     :else 0.0)]
            (vfx-hand/add-camera-pitch-delta! (:queue-owner base-meta) (* -0.2 pitch-factor))))
        (merge base-meta
               {:charge-ticks next-val
                :perform-ticks (long (or (:perform-ticks state*) 0))
                :active? true}))

      :perform
      (merge base-meta
             {:charge-ticks (long (or (:charge-ticks state*) 0))
              :perform-ticks perform-ticks-count
              :active? false})

      :end
      (when-not performed? {})

      state*)))

(defn- hand-tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance."
  [state]
  (let [state* (or state {})
        remaining (long (or (:perform-ticks state*) 0))]
    (when (pos? remaining)
      (vfx-hand/add-camera-pitch-delta! (:queue-owner state*) perform-step))
    (if (> remaining 1)
      (assoc state* :perform-ticks (dec remaining))
      (when (or (:active? state*) (pos? remaining))
        (assoc state* :perform-ticks 0)))))

;; ---------------------------------------------------------------------------
;; FX channel registration
;; ---------------------------------------------------------------------------

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:groundshock :level] [_ _] nil)
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:groundshock :hand] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:groundshock :level]
  [_ _ store ctx-id channel owner-key payload] (level-enqueue! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:groundshock :hand]
  [_ _ store ctx-id channel owner-key payload] (hand-enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:groundshock :level] [_ _ store] (level-tick! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:groundshock :hand] [_ _ store] (hand-tick-state! store))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
