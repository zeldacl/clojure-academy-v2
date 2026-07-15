(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.vec-accel
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str])
  (:import [cn.li.mcmod.math V3]))

(def ^:private sound-id (modid/namespaced-path "vecmanip.vec_accel"))









(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store {:effect-state {}})
				owner-key* (or owner-key [:ctx ctx-id])
				{:keys [mode charge-ticks can-perform? look-dir init-vel source-player-id world-id]} (or payload {})
				base-meta {:owner-key owner-key*
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:start
			(assoc-in store* [:effect-state owner-key*]
								(merge base-meta
											 {:active? true :charge-ticks 0
												:can-perform? false
												:look-dir {:x 0.0 :y 0.0 :z 1.0}
												:init-vel {:x 0.0 :y 0.0 :z 1.0}}))
			:update
			(update-in store* [:effect-state owner-key*]
				(fn [st]
					(assoc (merge base-meta (or st {:active? true}))
								 :owner-key owner-key*
								 :ctx-id ctx-id
								 :channel channel
								 :source-player-id source-player-id
								 :world-id world-id
								 :active? true
								 :charge-ticks (long (or charge-ticks 0))
								 :can-perform? (boolean can-perform?)
								 :look-dir (or look-dir {:x 0.0 :y 0.0 :z 1.0})
								 :init-vel (or init-vel {:x 0.0 :y 0.0 :z 1.0}))))
			:perform
			(do
				(client-sounds/queue-current-sound-effect!
					{:type :sound :sound-id sound-id :volume 0.35 :pitch 1.0})
				store*)
			:end
			(assoc-in store* [:effect-state owner-key*]
								(merge base-meta {:active? false}))
			store*)))

(defn- tick-state!
	[store]
	(or store {:effect-state {}}))

(defn- trajectory-ops
	[^V3 camera-pos effect]
	(when (and effect (:active? effect))
		(let [init-vel  (vec3/map->v3 (or (:init-vel effect) {:x 0.0 :y 0.0 :z 1.0}))
					look-dir  (vec3/map->v3 (or (:look-dir effect) {:x 0.0 :y 0.0 :z 1.0}))
					can-do?   (boolean (:can-perform? effect))
					px        (.-x camera-pos)
					py        (- (.-y camera-pos) 1.62)
					pz        (.-z camera-pos)
					lx        (.-x look-dir)
					ly        (.-y look-dir)
					lz        (.-z look-dir)
					horiz-len (Math/sqrt (+ (* lx lx) (* lz lz)))
					safe-h    (max 1.0e-8 horiz-len)
					rot-x     (* (/ lz safe-h) -0.08)
					rot-z     (* (/ (- lx) safe-h) -0.08)
					off-x     (- rot-x (* lx 0.12))
					off-y     (- 1.56 (* ly 0.12))
					off-z     (- rot-z (* lz 0.12))
					start-pos (vec3/v3 (+ px off-x) (+ py off-y) (+ pz off-z))
					h         0.02
					dt        0.02]
			(loop [^V3 pos   start-pos
						 ^V3 vel   init-vel
						 ^V3 prev  nil
						 ops   (transient [])
						 idx   0]
				(let [vel2  (vec3/v3 (* (.-x vel) 0.98) (* (.-y vel) 0.98) (* (.-z vel) 0.98))
							pos2  (vec3/v3 (+ (.-x pos) (* (.-x vel2) dt))
														(+ (.-y pos) (* (.-y vel2) dt))
														(+ (.-z pos) (* (.-z vel2) dt)))
							vel3  (vec3/v3 (.-x vel2) (- (.-y vel2) (* dt 1.9)) (.-z vel2))]
					(when (and prev (< idx 99))
						(let [a-int (fx-beam/fade-alpha idx)]
							(when (> a-int 0)
								(let [color (fx-beam/rgba
															(if can-do?
																{:r 255 :g 255 :b 255}
																{:r 255 :g 51 :b 51})
															a-int)
											p0 (vec3/v3 (.-x prev) (+ (.-y prev) h) (.-z prev))
											p1 (vec3/v3 (.-x pos) (+ (.-y pos) h) (.-z pos))
											p2 (vec3/v3 (.-x pos) (- (.-y pos) h) (.-z pos))
											p3 (vec3/v3 (.-x prev) (- (.-y prev) h) (.-z prev))]
									(conj! ops (fx-beam/glow-line-quad-op p0 p1 p2 p3 color))))))
					(if (>= idx 99)
						(persistent! ops)
						(recur pos2 vel3 pos ops (inc idx))))))))

(defn- build-plan
	[camera-pos _hand-center-pos _tick]
	(let [^V3 cam-v (vec3/map->v3 camera-pos)
				ops (mapcat #(trajectory-ops cam-v %)
										(filter #(get % :active?) (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :vec-accel)))))]
		(when (seq ops)
			{:ops (vec ops)})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:vec-accel :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:vec-accel :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:vec-accel :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :vec-accel [_ store owner-key]
  (update store :effect-state dissoc owner-key))
