(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.blood-retrograde
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
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
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam])
  (:import [cn.li.mcmod.math V3]))

(def ^:private sound-id (modid/namespaced-path "vecmanip.blood_retro"))
(def ^:private splash-life 10)
(def ^:private spray-life 1200)

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state/:splashes/
;; :sprays owner-map wrapping (owner isolation comes from instance identity
;; itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration.
;; combat_content.clj's :blood-retrograde skill sends exactly ONE :vfx step,
;; ever: :event :perform from its :release phase, with
;; :params {:max-charge-ticks 30} -- no :start/:update/:end, and none of the
;; :ticks/:charge-ratio/:sound-pos/:splashes/:sprays fields the :perform
;; branch itself reads. In production today :perform still sets :active?
;; true (the branch's default merge, unconditional) and :performed? true,
;; but splash-events/spray-events are always empty (mapv on a nil coll is
;; []) so the sound never plays and no splash/spray ever gets queued. Since
;; :end never fires either, :active? never goes back to false -- meaning
;; build-plan's walk-speed override below DOES apply after the first cast,
;; permanently, at :ticks 0 (local-walk-speed 0.1, since :ticks never
;; advances -- :start/:update, which would have bumped it, never fire). Not
;; something this migration is scoped to fix -- migrated structurally only.
(defn- enqueue-state!
	[store ctx-id channel _owner-key payload]
	(let [store* (or store {})
				{:keys [mode ticks charge-ratio performed? sound-pos splashes sprays source-player-id world-id]}
				(or payload {})
				base-meta {:ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:start
			(merge store* base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})
			:update
			(assoc (merge base-meta store*)
						 :active? true
						 :ticks (long (or ticks 0))
						 :charge-ratio (double (or charge-ratio 0.0))
						 :performed? false)
			:perform
			(let [splash-events (mapv (fn [splash]
																	(merge base-meta splash {:ttl splash-life :max-ttl splash-life}))
																(or splashes (:splashes payload)))
						spray-events (mapv (fn [spray]
																 (merge base-meta spray {:ttl spray-life :max-ttl spray-life}))
															 (or sprays (:sprays payload)))
						updated-store (-> store*
															(update :splashes (fnil into []) splash-events)
															(update :sprays (fnil into []) spray-events))]
				(when (or (seq splash-events) (seq spray-events))
					(client-sounds/queue-current-sound-effect!
						{:type :sound :sound-id sound-id :volume 1.0 :pitch 1.0
						 :x (double (or (some-> sound-pos :x) 0.0))
						 :y (double (or (some-> sound-pos :y) 0.0))
						 :z (double (or (some-> sound-pos :z) 0.0))}))
				(assoc (merge base-meta updated-store) :active? true :performed? true))
			:end
			(assoc (merge base-meta store*) :active? false :ticks 0 :charge-ratio 0.0
						 :performed? (boolean performed?))
			store*)))

(defn- tick-state!
	"Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Stay alive while :active? or a splash/spray is still
   animating, mirroring vec_deviation's tick-state!."
	[store]
	(let [store* (or store {})
				active? (boolean (:active? store*))
				splashes (store-tick/tick-ttl-vec (:splashes store*))
				sprays (store-tick/tick-ttl-vec (:sprays store*))]
		(when (or active? (seq splashes) (seq sprays))
			(cond-> (assoc store* :splashes splashes :sprays sprays)
				active? (update :ticks (fnil inc 0))))))

(defn- splash-ops [^V3 cam-pos {:keys [x y z size ttl max-ttl]}]
	(let [center (vec3/v3 (double x) (double y) (double z))
				half-size (* 0.5 (double (or size 1.0)))
				right (ru/camera-facing-right-axis center cam-pos)
				up (ru/billboard-up-axis center cam-pos right)
				side (vec3/v* right half-size)
				lift (vec3/v* up half-size)
				p0 (vec3/v+ (vec3/v- center side) lift)
				p1 (vec3/v+ (vec3/v+ center side) lift)
				p2 (vec3/v- (vec3/v+ center side) lift)
				p3 (vec3/v- (vec3/v- center side) lift)
				age (long (- (long (or max-ttl splash-life)) (long (or ttl splash-life))))
				frame (max 0 (min 9 age))]
		[(ru/quad-op (str (modid/namespaced-path "textures/effects/blood_splash/") frame ".png")
								 p0 p1 p2 p3
								 {:r 213 :g 29 :b 29 :a 200})]))

(defn- spray-face-basis [face]
	(case face
		:up    [(vec3/v3 0.0 1.0 0.0) (vec3/v3 1.0 0.0 0.0) (vec3/v3 0.0 0.0 1.0)]
		:down  [(vec3/v3 0.0 -1.0 0.0) (vec3/v3 1.0 0.0 0.0) (vec3/v3 0.0 0.0 -1.0)]
		:north [(vec3/v3 0.0 0.0 -1.0) (vec3/v3 1.0 0.0 0.0) (vec3/v3 0.0 1.0 0.0)]
		:south [(vec3/v3 0.0 0.0 1.0) (vec3/v3 -1.0 0.0 0.0) (vec3/v3 0.0 1.0 0.0)]
		:west  [(vec3/v3 -1.0 0.0 0.0) (vec3/v3 0.0 0.0 -1.0) (vec3/v3 0.0 1.0 0.0)]
		:east  [(vec3/v3 1.0 0.0 0.0) (vec3/v3 0.0 0.0 1.0) (vec3/v3 0.0 1.0 0.0)]
		[(vec3/v3 0.0 1.0 0.0) (vec3/v3 1.0 0.0 0.0) (vec3/v3 0.0 0.0 1.0)]))

(defn- spray-ops [{:keys [x y z face size rotation offset-u offset-v texture-id ttl]}]
	(let [[normal tangent bitangent] (spray-face-basis face)
				center (vec3/v+
								 (vec3/v3 (+ (double x) 0.5) (+ (double y) 0.5) (+ (double z) 0.5))
								 (vec3/v* normal 0.51))
				tangent' (vec3/rotate-around-axis tangent normal (double (or rotation 0.0)))
				bitangent' (vec3/rotate-around-axis bitangent normal (double (or rotation 0.0)))
				shifted (vec3/v+ center
											 (vec3/v+ (vec3/v* tangent' (double (or offset-u 0.0)))
															(vec3/v* bitangent' (double (or offset-v 0.0)))))
				half-size (* 0.5 (double (or size 1.0)))
				side (vec3/v* tangent' half-size)
				lift (vec3/v* bitangent' half-size)
				p0 (vec3/v+ (vec3/v- shifted side) lift)
				p1 (vec3/v+ (vec3/v+ shifted side) lift)
				p2 (vec3/v- (vec3/v+ shifted side) lift)
				p3 (vec3/v- (vec3/v- shifted side) lift)
				life (if (and ttl (< ttl 60)) (/ (double ttl) 60.0) 1.0)
				tex-folder (if (contains? #{:up :down} face) "wall" "grnd")
				tex-index (max 0 (min 2 (long (or texture-id 0))))]
		[(ru/quad-op (str (modid/namespaced-path "textures/effects/blood_spray/") tex-folder "/" tex-index ".png")
								 p0 p1 p2 p3
								 {:r 255 :g 255 :b 255 :a (int (+ 40 (* 180 life)))})]))

(defn- local-walk-speed [ticks]
	(let [ratio (min 1.0 (/ (double ticks) 20.0))]
		(float (+ 0.1 (* (- 0.007 0.1) ratio)))))

(defn- build-plan
	[camera-pos _hand-center-pos _tick]
	(let [br (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :blood-retrograde)
				br (when (:active? br) br)
				current-splashes (:splashes br)
				current-sprays (:sprays br)
				^V3 cam-v (vec3/map->v3 camera-pos)
				splash-plan (mapcat #(splash-ops cam-v %) current-splashes)
				spray-plan (mapcat spray-ops current-sprays)
				ws (when br (local-walk-speed (:ticks br)))]
		(when (or (seq splash-plan) (seq spray-plan) ws)
			{:ops (vec (concat splash-plan spray-plan))
			 :local-walk-speed ws})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:blood-retrograde :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:blood-retrograde :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:blood-retrograde :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :blood-retrograde
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
;; Splashes/sprays were already the port's OWN client state (not spawned
;; world entities upstream keeps track of separately), so there is nothing
;; external for a teardown hook to release either.
