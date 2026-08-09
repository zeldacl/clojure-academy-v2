(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.plasma-cannon
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.tornado :as tornado]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private loop-sound (modid/namespaced-path "vecmanip.plasma_cannon"))
(def ^:private charged-sound (modid/namespaced-path "vecmanip.plasma_cannon_t"))
(defn- loop-sound-key [ctx-id] (str "plasma-cannon/" ctx-id))

;; ---------------------------------------------------------------------------
;; Charge tornado (original PlasmaCannon's private Tornado LocalEntity)
;; ---------------------------------------------------------------------------

;; Original: new TornadoEffect(12, 8, 1, 0.3) — one column, far bigger than
;; Storm Wing's four, standing on the ground under the charge position.
(def ^:private tornado-params {:ht 12.0 :sz 8.0 :dscale 0.3})

;; Tornado.alpha: fade in over the first 20 ticks, and once dead fade back out
;; over 20 more (setDead at 30). onUpdate then halves it: alpha * 0.5.
(def ^:private tornado-fade-ticks 20.0)
(def ^:private tornado-dead-ticks 30)









(defn- tornado-base-fallback
	"Used only when the start payload carried no ground point: the original's
	miss case puts the column at the bottom of the 20-block downward ray."
	[charge-pos]
	(when (map? charge-pos)
		{:x (double (:x charge-pos))
		 :y (- (double (:y charge-pos)) 20.0)
		 :z (double (:z charge-pos))}))

(defn- tornado-alpha
	"Tornado.alpha times the 0.5 applied in onUpdate. Fades in over the first 20
	ticks; once dead (original: `ctx.state == STATE_GO`) it fades back out over
	20 more."
	^double [^long ticks ^long dead-ticks dead?]
	(* 0.5
		 (if dead?
			 (max 0.0 (- 1.0 (/ (double dead-ticks) tornado-fade-ticks)))
			 (min 1.0 (/ (double ticks) tornado-fade-ticks)))))

(defn- tick-tornado
	"Advance one owner's charge tornado: age it, sample its rings for this tick,
	and drop it 30 ticks after it died (original setDead)."
	[st ^long ticks]
	(if-not (:tornado st)
		st
		(let [dead? (boolean (or (:tornado-dead? st) (= :go (:state st))))
					dead-ticks (if dead? (inc (long (or (:tornado-dead-ticks st) 0))) 0)]
			(if (>= dead-ticks tornado-dead-ticks)
				(dissoc st :tornado :tornado-rings :tornado-alpha :tornado-base)
				(assoc st
							 :tornado-dead? dead?
							 :tornado-dead-ticks dead-ticks
							 :tornado-alpha (tornado-alpha ticks dead-ticks dead?)
							 :tornado-rings (tornado/ring-states (tornado/effect-time ticks)
																									 tornado-params
																									 (:tornado st)))))))

(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store {:effect-state {}})
				owner-key* (or owner-key [:ctx ctx-id])
				{:keys [mode charge-ticks fully-charged? charge-pos tornado-base flight-ticks
								state destination pos performed? source-player-id world-id]} (or payload {})
				base-meta {:owner-key owner-key*
									 :queue-owner (client-particles/current-effect-owner)
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:start
			(do
				;; Original c_begin: FollowEntitySound charge loop attached to
				;; the caster until the context ends — not a re-queued one-shot.
				(client-bridge/run-client-effect!
				 :mcmod/start-loop-sound-at-player
				 {:key (loop-sound-key ctx-id)
				  :sound-id loop-sound
				  :owner-uuid (str source-player-id)
				  :volume 0.5
				  :pitch 1.0})
				(assoc-in store* [:effect-state owner-key*]
									(merge base-meta
												 {:active? true :charge-ticks 0 :charge-pos (:charge-pos payload)
												:flight-ticks 0 :state :charging :destination nil
												:performed? false
												;; Original c_begin also spawns the Tornado entity, seated on
												;; the ground under the charge position and fading in from 0.
												:tornado (tornado/new-column tornado-params)
												:tornado-base (or tornado-base (tornado-base-fallback charge-pos))
												:tornado-dead-ticks 0})))
			:update
			(let [updated (assoc-in store* [:effect-state owner-key*]
												(assoc (merge base-meta (or (get-in store* [:effect-state owner-key*]) {}))
													 :owner-key owner-key*
													 :ctx-id ctx-id
													 :channel channel
													 :source-player-id source-player-id
													 :world-id world-id
													 :active? true
													 :charge-ticks (long (or charge-ticks 0))
													 :flight-ticks (long (or flight-ticks 0))
													 :state (or state :charging)
													 :charge-pos (or charge-pos (get-in store* [:effect-state owner-key* :charge-pos]))
													 :destination (or destination (get-in store* [:effect-state owner-key* :destination]))
 ;; Server sync landed: keep the previous authoritative position so the
 ;; renderer can interpolate BETWEEN the last two syncs (standard two-sample
 ;; interpolation) — continuous glide, no jump when a new sync arrives.
 :prev-charge-pos (get-in store* [:effect-state owner-key* :charge-pos])
 :sync-ms (System/currentTimeMillis)))]
					(when (boolean fully-charged?)
						(client-sounds/queue-sound-effect! (:queue-owner base-meta)
							{:type :sound :sound-id charged-sound :volume 0.5 :pitch 1.0}))
					updated)
			:perform
			(do
				(when (map? pos)
					(let [tx (double (:x pos)) ty (double (:y pos)) tz (double (:z pos))]
						(client-particles/queue-particle-effect! (:queue-owner base-meta)
							{:type :particle :particle-type :explosion-large
							 :x tx :y ty :z tz :count 1 :speed 0.0
							 :offset-x 0.0 :offset-y 0.0 :offset-z 0.0})
						(dotimes [_ 12]
							(client-particles/queue-particle-effect! (:queue-owner base-meta)
								{:type :particle :particle-type :smoke-large
								 :x (+ tx (- (* (rand) 10.0) 5.0))
								 :y (+ ty (- (* (rand) 5.0) 2.5))
								 :z (+ tz (- (* (rand) 10.0) 5.0))
								 :count 1 :speed (+ 0.1 (* (rand) 0.3))
								 :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
						(client-sounds/queue-sound-effect! (:queue-owner base-meta)
							{:type :sound :sound-id "minecraft:entity.generic.explode"
							 :volume 3.0 :pitch 0.8 :x tx :y ty :z tz})))
				store*)
			:end
			(do
				;; Original c_terminate: sound.stop()
				(client-bridge/run-client-effect!
				 :mcmod/stop-loop-sound
				 {:key (loop-sound-key ctx-id)})
				(assoc-in store* [:effect-state owner-key*]
									(merge base-meta {:active? false :performed? (boolean performed?)})))
			store*)))

(defn- tick-state!
	[store]
	(let [store* (or store {:effect-state {}})]
		(update store* :effect-state
			(fn [states]
				(store-tick/map-active-states
				 states
				 (fn [_owner-key st]
					 (let [ticks (inc (long (or (:ticks st) 0)))]
						 ;; The charge loop is a continuous FollowEntitySound
						 ;; started on :start and stopped on :end — no re-queue.
						 (let [cp (:charge-pos st)]
							 (when (and cp (= :go (:state st)))
								 (client-particles/queue-particle-effect! (:queue-owner st)
									 {:type :particle :particle-type :flame
										:x (double (:x cp)) :y (double (:y cp)) :z (double (:z cp))
										:count 4 :speed 0.2
										:offset-x 0.5 :offset-y 0.5 :offset-z 0.5})))
						 (tick-tornado (assoc st :ticks ticks) ticks))))))))

(defn- plasma-balls
	[{:keys [x y z]} ticks state]
	(let [t (double (or ticks 0))
				phase (* 0.23 t)
				state-mul (if (= state :go) 1.25 1.0)
				base-r (* 0.55 state-mul)
				h-wave (* 0.08 (Math/sin (* 0.15 t)))]
		[{:x x :y (+ y h-wave) :z z :size (* 0.95 state-mul)}
		 {:x (+ x (* base-r (Math/cos (+ phase 0.0))))
			:y (+ y (* 0.18 (Math/sin (+ phase 0.7))))
			:z (+ z (* base-r (Math/sin (+ phase 0.0))))
			:size 0.62}
		 {:x (+ x (* base-r (Math/cos (+ phase 2.09))))
			:y (+ y (* 0.18 (Math/sin (+ phase 2.79))))
			:z (+ z (* base-r (Math/sin (+ phase 2.09))))
			:size 0.62}
		 {:x (+ x (* base-r (Math/cos (+ phase 4.18))))
			:y (+ y (* 0.18 (Math/sin (+ phase 4.91))))
			:z (+ z (* base-r (Math/sin (+ phase 4.18))))
			:size 0.62}
		 {:x (+ x (* 0.35 (Math/cos (* 0.41 t))))
			:y (+ y (* 0.25 (Math/sin (* 0.47 t))))
			:z (+ z (* 0.35 (Math/sin (* 0.41 t))))
			:size 0.45}
		 {:x (+ x (* 0.3 (Math/cos (+ (* 0.53 t) 1.3))))
			:y (+ y (* 0.22 (Math/sin (+ (* 0.59 t) 0.4))))
			:z (+ z (* 0.3 (Math/sin (+ (* 0.53 t) 1.3))))
			:size 0.4}]))

(defn- tornado-ops
	"The charge tornado's ring quads. TornadoEntityRenderer only translates to
	the entity position before TornadoRenderer.doRender, so the column stands
	upright in world space — no rotation to fold in, unlike Storm Wing's."
	[st]
	(let [base (:tornado-base st)
				rings (:tornado-rings st)]
		(when (and (map? base) (seq rings))
			(tornado/ring-quad-ops (double (:x base)) (double (:y base)) (double (:z base))
														 tornado/identity-linear
														 (double (or (:tornado-alpha st) 0.0))
														 rings))))

(defn- plasma-state-ops
	[st]
	(when (and (:active? st) (map? (:charge-pos st)))
		(let [cp (:charge-pos st)
					ticks (long (or (:ticks st) 0))
					state (:state st)
					charge-ticks (long (or (:charge-ticks st) 0))
					ramp (min 1.0 (/ charge-ticks 24.0))
					alpha (double (* (if (= state :go) 1.0 0.85) (+ 0.2 (* 0.8 ramp))))
					radius (+ 0.95 (* 0.35 ramp) (* 0.08 (Math/sin (* 0.21 ticks))))
					balls (plasma-balls cp ticks state)]
			[{:kind :plasma-body
				:center {:x (double (:x cp)) :y (double (:y cp)) :z (double (:z cp))}
				:radius (double radius)
				:alpha (double alpha)
				:balls balls}])))

(defn- rendered-charge-pos
	"Standard two-sample interpolation between the last two server syncs
	(prev-charge-pos -> charge-pos over the 250ms sync interval). The render
	position always lives BETWEEN two authoritative syncs, so it is continuous
	when a new sync lands — the earlier wall-clock extrapolation jumped back
	to the fresh sync position every 5 ticks, reading as a stutter."
	[st]
	(let [prev (:prev-charge-pos st)
				cur (:charge-pos st)
				sync-ms (long (or (:sync-ms st) 0))
				now (System/currentTimeMillis)
				t (max 0.0 (min 1.0 (/ (double (- now sync-ms)) 250.0)))]
		(if (and prev cur (pos? (- (long now) (long sync-ms))))
			{:x (+ (double (:x prev)) (* t (- (double (:x cur)) (double (:x prev)))))
			 :y (+ (double (:y prev)) (* t (- (double (:y cur)) (double (:y prev)))))
			 :z (+ (double (:z prev)) (* t (- (double (:z cur)) (double (:z prev)))))}
			cur)))

(defn- build-plan
	[_camera-pos _hand-center-pos _tick]
	(let [state (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :plasma-cannon)))
				;; Render at the interpolated position without writing it back —
				;; the state stays authoritative (server syncs only).
				ops (mapcat (fn [st]
												;; The tornado is seated on the ground at :start and never moves,
												;; so the flight interpolation applies to the plasma body only.
												(concat (tornado-ops st)
																(plasma-state-ops (assoc st :charge-pos (rendered-charge-pos st)))))
										state)]
		(when (seq ops)
			{:ops (vec ops)})))


(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:plasma-cannon :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:plasma-cannon :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:plasma-cannon :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :plasma-cannon
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :plasma-cannon [_ store owner-key]
  (client-bridge/run-client-effect!
   :mcmod/stop-loop-sound
   {:key (loop-sound-key (second owner-key))})
  (update store :effect-state dissoc owner-key))
