(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.vec-deviation
  "Client FX for VecDeviation: the same WaveEffect VecReflection spawns, with
  one ring instead of two and 0.6 instead of 1.1."
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.wave-effect :as wave]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private sound-id (modid/namespaced-path "vecmanip.vec_deviation"))

;; VecDeviation spawns WaveEffect(world, rings = 1, size = 0.6).
(def ^:private wave-rings 1)
(def ^:private wave-size 0.6)

(defn- queue-deviation-sound!
  [x y z]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
     :x (double (or x 0.0)) :y (double (or y 0.0)) :z (double (or z 0.0))}))









;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own {:active? :ticks :waves [...]} directly -- no
;; more owner-key map wrapping (owner isolation comes from instance
;; identity itself). :stop-entity/:play modes are never sent by current
;; combat-core content (combat_content.clj's :vec-deviation only emits
;; :start/:update/:end -- :update itself isn't handled here either, always
;; fell through to the no-op default branch before this migration too) --
;; preserved as dead branches exactly as before; not something this
;; migration is scoped to fix.
(defn- enqueue-state!
  [store _ctx-id _channel _owner-key payload]
  (let [store* (or store {:active? false :ticks 0 :waves []})
        {:keys [mode x y z marked? entity-uuid yaw-rad pitch-rad]} (or payload {})]
    (case mode
      :start
      (assoc store* :active? true :ticks 0)
      :end
      (assoc store* :active? false)
      :stop-entity
      (do
        ;; c_stopEntity zeroes the entity's motion OUTSIDE the isMarked branch:
        ;; the projectile stops locally the instant the message lands, marked
        ;; or not, instead of coasting until the next velocity sync.
        (when entity-uuid
          (client-bridge/run-client-effect!
            :mcmod/set-client-entity-motion
            {:entity-uuid (str entity-uuid) :vx 0.0 :vy 0.0 :vz 0.0}))
        (if marked?
          (do
            ;; ...and the wave and its sound only when it is.
            (queue-deviation-sound! x y z)
            (update store* :waves (fnil conj [])
              {:x (double (or x 0.0))
               :y (double (or y 0.0))
               :z (double (or z 0.0))
               :yaw-rad (double (or yaw-rad 0.0))
               :pitch-rad (double (or pitch-rad 0.0))
               :rings (wave/build-rings wave-rings wave-size)
               :ttl wave/life-ticks
               :max-ttl wave/life-ticks}))
          store*))
      :play
      (do
        (queue-deviation-sound! x y z)
        store*)
      store*)))

(defn- tick-state!
  "Returning nil ends the instance (vfx-core's own natural-lifetime rule --
   see vfx-core/runtime.clj's tick-instance). Stay alive while either the
   session is still active or an already-thrown wave is still animating,
   so a wave fired right before :end still finishes its TTL instead of
   vanishing with its parent instance."
  [store]
  (let [state* (or store {:active? false :ticks 0 :waves []})
        active? (boolean (:active? state*))
        waves (store-tick/tick-ttl-vec (:waves state*))]
    (when (or active? (seq waves))
      (assoc state* :waves waves
             :ticks (if active? (inc (long (or (:ticks state*) 0))) (:ticks state*))))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (let [{:keys [waves]} (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :vec-deviation)
        wave-plan (mapcat wave/ops waves)]
    (when (seq wave-plan)
      {:ops (vec wave-plan)})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:vec-deviation :level] [_ _] {:active? false :ticks 0 :waves []})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:vec-deviation :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:vec-deviation :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :vec-deviation
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
;; No effect-clear-owner! override anymore -- :clear-owner-fn (the
;; :singleton-era per-owner-map cleanup hook) has no live caller for any
;; effect (see the destroy-hook commit for why), and dissoc-ing a
;; nonexistent owner-map key against this flat state would be a no-op at
;; best. vfx-core's real destroy!/clear-owner! now reach this instance
;; correctly via instance identity; no per-effect hook needed since there's
;; no side-effecting resource here to release on teardown.
