(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-manip
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

(def ^:private hold-loop-sound (modid/namespaced-path "em.lf_loop"))
(def ^:private perform-sound (modid/namespaced-path "em.mag_manip"))
(defn- loop-sound-key [ctx-id] (str "mag-manip/" ctx-id))

(defn- start-hold-loop! [ctx-id source-player-id]
  ;; Original MagManipContextC: FollowEntitySound(player, "em.lf_loop").setLoop()
  ;; started on MSG_MADEALIVE and stopped in c_terminate. Queuing it as
  ;; one-shots instead left the last sample playing past the end of the skill
  ;; with no handle to stop it — and em.lf_loop is a loop sample.
  (try
    (client-bridge/run-client-effect!
     :mcmod/start-loop-sound-at-player
     {:key (loop-sound-key ctx-id)
      :sound-id hold-loop-sound
      :owner-uuid (str source-player-id)
      :volume 0.5
      :pitch 1.0})
    (catch Throwable _ nil)))

(defn- stop-hold-loop! [ctx-id]
  (try
    (client-bridge/run-client-effect!
     :mcmod/stop-loop-sound
     {:key (loop-sound-key ctx-id)})
    (catch Throwable _ nil)))

(def ^:private default-state
	{:active? false
	 :focus nil
	 :block-id nil
	 :ticks 0})

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :states owner-map
;; wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration.
;; combat_content.clj's :mag-manip skill sends exactly ONE :vfx step, ever:
;; :event :throw from its :release phase, with :params {:throw-range 20.0}
;; -- no :hold-start/:hold-loop/:end, and no :focus/:block-id. So
;; start-hold-loop!/stop-hold-loop! never actually run (matches B 类的
;; "conservative implementation" note -- the hold loop sound was never
;; wired live); :throw itself DOES fire, but only ever creates a fresh
;; default-state with :active? false (nothing upstream ever set it true),
;; so current-hand-transform's `(when (:active? state) ...)` guard never
;; passes either. Only perform-sound actually plays. Migrated structurally
;; only.
(defn- enqueue-state! [store ctx-id channel _owner-key payload]
	(let [store* (or store default-state)
				{:keys [mode focus block-id source-player-id world-id]} payload
				base-meta {:ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:hold-start
			(do
				(start-hold-loop! ctx-id source-player-id)
				(merge default-state base-meta
							 {:active? true
								:focus focus
								:block-id block-id
								:ticks 0}))

			:hold-loop
			(-> (merge default-state store* base-meta)
					(assoc :active? true)
					(cond-> focus (assoc :focus focus))
					(cond-> block-id (assoc :block-id block-id)))

			:throw
			(do
				(client-sounds/queue-current-sound-effect!
					{:type :sound :sound-id perform-sound :volume 0.9 :pitch 1.0})
				(merge default-state store* base-meta {:active? false}))

			:end
			(do
				(stop-hold-loop! ctx-id)
				(merge default-state base-meta))

			store*)))

(defn- tick-state!
	"Preserved exactly as it was before this migration: never signals a
   natural end (the original never dropped an owner from :ttl/:active?
   alone, only via :end's dissoc -- and :end never actually fires, see
   enqueue-state! above). Lives until the owner disconnects, same as
   before."
	[store]
	(let [store* (or store default-state)]
		;; The hold loop is one continuous FollowEntitySound started on
		;; :hold-start and stopped on :end.
		(if (:active? store*)
			(assoc store* :ticks (inc (long (or (:ticks store*) 0))))
			store*)))

(defn- destroy-fx!
	"vfx-core's :destroy hook: release the hold loop on any teardown path
   that isn't the normal :end case above (which already stops it) --
   explicit :destroy signal, clear-owner!/clear-world!, or :update itself
   returning nil. Currently unreachable in practice (see enqueue-state!'s
   comment -- :active? never actually goes true today), kept for
   correctness the same way mag_movement.clj's destroy-fx! is."
	[state]
	(when (:active? state)
		(stop-hold-loop! (:ctx-id state))))

(defn- current-hand-transform []
	(when-let [uuid (client-bridge/local-player-uuid)]
		(let [state (vfx-hand/instance-for-owner :mag-manip uuid)]
			(when (:active? state)
				(let [ticks (double (or (:ticks state) 0))
							phase (* 0.22 ticks)
							y (+ 0.02 (* 0.01 (Math/sin phase)))]
					{:tx 0.0 :ty y :tz 0.0 :rot-x 0.0 :rot-y 0.0 :rot-z 0.0})))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-manip :hand] [_ _] default-state)
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-manip :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-manip :hand] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-transform-fn :mag-manip [_effect-id] (current-hand-transform))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-destroy! :mag-manip [_ state] (destroy-fx! state))
;; No effect-clear-owner! override anymore -- superseded by :destroy-fn
;; above (build-spec wires it unconditionally via dispatch-destroy!), which
;; vfx-core's real destroy!/clear-owner! now reach correctly per instance.
