(ns cn.li.ac.content.ability.electromaster.mag-manip
	"MagManip skill port for Electromaster.

	This implementation keeps the original gameplay intent in the current
	architecture: grab a magnetizable block, hold it in front of the player,
	then throw it on key release.

	No Minecraft imports."
	(:require [clojure.string :as str]
						[cn.li.ac.ability.player-state :as ps]
						[cn.li.ac.ability.service.learning :as learning]
						[cn.li.ac.ability.service.cooldown :as cd]
						[cn.li.ac.ability.event :as ability-evt]
						[cn.li.ac.ability.context :as ctx]
						[cn.li.mcmod.platform.raycast :as raycast]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.mcmod.platform.entity-damage :as entity-damage]
						[cn.li.mcmod.platform.world-effects :as world-effects]
						[cn.li.mcmod.platform.block-manipulation :as block-manip]
						[cn.li.mcmod.util.log :as log]))

; Original: traceLiving(player, 10, ...) = 10 blocks
(def ^:private max-grab-range 10.0)
; Original: getLookingPos(player, 20) = 20 blocks
(def ^:private max-throw-range 20.0)
; Original: multiply(player.getLookVec(), 2.0)
(def ^:private hold-distance 2.0)
; Original: subtract(entityHeadPos(player), new Vec3d(0, 0.1, 0)) -> -0.1 on Y
(def ^:private hold-head-y-offset 0.1)
(def ^:private throw-hit-radius 1.15)

(def ^:private strong-metal-blocks
	#{"minecraft:iron_block"
		"minecraft:iron_ore"
		"minecraft:deepslate_iron_ore"
		"minecraft:gold_block"
		"minecraft:gold_ore"
		"minecraft:deepslate_gold_ore"
		"minecraft:copper_block"
		"minecraft:copper_ore"
		"minecraft:deepslate_copper_ore"
		"minecraft:netherite_block"
		"minecraft:ancient_debris"
		"minecraft:anvil"
		"minecraft:chipped_anvil"
		"minecraft:damaged_anvil"
		"minecraft:hopper"
		"minecraft:chain"
		"minecraft:iron_bars"
		"minecraft:iron_door"
		"minecraft:iron_trapdoor"
		"minecraft:rail"
		"minecraft:powered_rail"
		"minecraft:detector_rail"
		"minecraft:activator_rail"})

(def ^:private weak-metal-hints
	["iron" "gold" "copper" "rail" "anvil" "chain" "ore" "debris" "metal" "steel"])

(defn- lerp
	[a b t]
	(+ a (* (- b a) (double t))))

(defn- v+
	[a b]
	{:x (+ (double (:x a)) (double (:x b)))
	 :y (+ (double (:y a)) (double (:y b)))
	 :z (+ (double (:z a)) (double (:z b)))} )

(defn- v-
	[a b]
	{:x (- (double (:x a)) (double (:x b)))
	 :y (- (double (:y a)) (double (:y b)))
	 :z (- (double (:z a)) (double (:z b)))} )

(defn- v*
	[a s]
	{:x (* (double (:x a)) (double s))
	 :y (* (double (:y a)) (double s))
	 :z (* (double (:z a)) (double s))})

(defn- dot
	[a b]
	(+ (* (:x a) (:x b))
		 (* (:y a) (:y b))
		 (* (:z a) (:z b))))

(defn- vlen
	[a]
	(Math/sqrt (dot a a)))

(defn- normalize
	[a]
	(let [len (max 1.0e-6 (vlen a))]
		(v* a (/ 1.0 len))))

(defn- floor-int [value]
	(int (Math/floor (double value))))

(defn- get-skill-exp
	[player-id]
	(when-let [state (ps/get-player-state player-id)]
		(get-in state [:ability-data :skills :mag-manip :exp] 0.0)))

(defn- player-pos
	[player-id]
	(get (ps/get-player-state player-id)
			 :position
			 {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id
	[player-id]
	(or (get-in (ps/get-player-state player-id) [:position :world-id])
			"minecraft:overworld"))

(defn- eye-pos
	[player-id]
	(let [{:keys [x y z]} (player-pos player-id)]
		{:x (double x) :y (+ (double y) 1.62) :z (double z)}))

(defn- look-dir
	[player-id]
	(when-let [look (when raycast/*raycast*
										(raycast/get-player-look-vector raycast/*raycast* player-id))]
		(normalize {:x (double (:x look))
								:y (double (:y look))
								:z (double (:z look))})))

; Original: origin = entityHeadPos(player) - (0, 0.1, 0); pos = origin + look * 2.0
(defn- hold-focus
	[player-id]
	(let [{:keys [x y z]} (player-pos player-id)
				origin {:x (double x) :y (- (+ (double y) 1.62) hold-head-y-offset) :z (double z)}
				dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})]
		(v+ origin (v* dir hold-distance))))

(defn- metal-block-id?
	[block-id exp]
	(let [id (some-> block-id str/lower-case)]
		(boolean
			(or (contains? strong-metal-blocks id)
					(and (>= exp 0.5)
							 (some #(str/includes? id %) weak-metal-hints))))))

(defn- restore-held-block!
	[{:keys [from-world? source-x source-y source-z block-id world-id]}]
	(when (and from-world?
						 block-manip/*block-manipulation*
						 block-id
						 (number? source-x)
						 (number? source-y)
						 (number? source-z))
		(let [bx (int source-x)
					by (int source-y)
					bz (int source-z)
					current (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)]
			(when (or (nil? current) (= current "minecraft:air"))
				(block-manip/set-block! block-manip/*block-manipulation* world-id bx by bz block-id)))))

(defn- pick-up-target-block
	[player-id exp]
	(when (and raycast/*raycast* block-manip/*block-manipulation*)
		(when-let [dir (look-dir player-id)]
			(let [start (eye-pos player-id)
						world-id (player-world-id player-id)
						hit (raycast/raycast-blocks raycast/*raycast*
																				world-id
																				(:x start) (:y start) (:z start)
																				(:x dir) (:y dir) (:z dir)
																				max-grab-range)]
				(when hit
					(let [bx (int (or (:x hit) 0))
								by (int (or (:y hit) 0))
								bz (int (or (:z hit) 0))
								block-id (or (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)
														 (:block-id hit))
								hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id bx by bz)]
						(when (and (string? block-id)
											 (metal-block-id? block-id exp)
											 (number? hardness)
											 (not (neg? (double hardness))))
							{:world-id world-id
							 :x bx :y by :z bz
							 :block-id block-id
							 :hardness hardness})))))))

; Original: (int) MathUtils.lerpf(60, 40, ctx.getSkillExp())
(defn- apply-mag-manip-cooldown!
	[player-id exp]
	(let [cd-ticks (int (Math/round (double (lerp 60.0 40.0 exp))))]
		(ps/update-cooldown-data! player-id cd/set-main-cooldown :mag-manip (max 1 cd-ticks))))

; Original: consumption = lerpf(140, 270, exp); overload = lerpf(35, 20, exp)
(defn- should-pay-up-cost?
	[player-id ctx-id]
	(if-let [ctx-data (ctx/get-context ctx-id)]
		(let [skill-state (:skill-state ctx-data)
					held-block (get skill-state :held-block)]
			(if-not (and (= :holding (:mode skill-state)) held-block)
				false
				(let [focus-pos (or (:focus skill-state) (hold-focus player-id))
							player-now (player-pos player-id)
							dist-sq (+ (Math/pow (- (:x player-now) (:x focus-pos)) 2.0)
												 (Math/pow (- (:y player-now) (:y focus-pos)) 2.0)
												 (Math/pow (- (:z player-now) (:z focus-pos)) 2.0))]
					(< dist-sq 25.0))))
		false))

(defn mag-manip-cost-up-cp
	[{:keys [player-id ctx-id]}]
	(if (should-pay-up-cost? player-id ctx-id)
		(lerp 140.0 270.0 (get-skill-exp player-id))
		0.0))

(defn mag-manip-cost-up-overload
	[{:keys [player-id ctx-id]}]
	(if (should-pay-up-cost? player-id ctx-id)
		(lerp 35.0 20.0 (get-skill-exp player-id))
		0.0))

(defn mag-manip-cost-creative?
	[{:keys [player]}]
	(boolean (and player (entity/player-creative? player))))

(defn- add-mag-manip-exp!
	[player-id amount]
	(when-let [state (ps/get-player-state player-id)]
		(let [{:keys [data events]} (learning/add-skill-exp
																	(:ability-data state)
																	player-id
																	:mag-manip
																	amount
																	1.0)]
			(ps/update-ability-data! player-id (constantly data))
			(doseq [e events]
				(ability-evt/fire-ability-event! e)))))

(defn- distance-to-segment
	[point seg-start seg-end]
	(let [ab (v- seg-end seg-start)
				ap (v- point seg-start)
				denom (max 1.0e-6 (dot ab ab))
				t (max 0.0 (min 1.0 (/ (dot ap ab) denom)))
				closest (v+ seg-start (v* ab t))]
		{:distance (vlen (v- point closest))
		 :t t}))

(defn- try-place-thrown-block!
	[world-id end-pos held-block-id]
	(when (and block-manip/*block-manipulation* world-id held-block-id)
		(let [bx (floor-int (:x end-pos))
					by (floor-int (:y end-pos))
					bz (floor-int (:z end-pos))
					current (block-manip/get-block block-manip/*block-manipulation* world-id bx by bz)]
			(when (or (nil? current) (= current "minecraft:air"))
				(block-manip/set-block! block-manip/*block-manipulation* world-id bx by bz held-block-id)
				true))))

; Original s_makeAlive: 1) check held item in hand, 2) fallback to raycast world block,
; 3) if neither found, terminate context.
(defn mag-manip-on-key-down
	[{:keys [player-id ctx-id player]}]
	(try
		(let [exp (get-skill-exp player-id)
					world-id (player-world-id player-id)
					;; First check: does player hold a metal block in main hand?
					hand-item-id (when player
												 (entity/player-get-main-hand-item-id player))
					held-metal? (and (string? hand-item-id)
											 (metal-block-id? hand-item-id exp))]
			(cond
				;; Case 1: holding a metal block in hand
				held-metal?
				(let [creative? (boolean (and player (entity/player-creative? player)))
							_ (when-not creative?
									(entity/player-consume-main-hand-item! player 1))
							focus (hold-focus player-id)]
					(ctx/update-context! ctx-id assoc :skill-state
												 {:skip-default-cooldown true
													:fired false
													:mode :holding
													:hold-ticks 0
													:held-block {:block-id hand-item-id
																	 :from-world? false
																	 :from-hand? true
																	 :world-id world-id}
													:focus focus})
					(ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
												 {:mode :hold-start
													:focus focus
													:block-id hand-item-id}))

				;; Case 2: raycast world block
				:else
				(if-let [{:keys [world-id x y z block-id]} (pick-up-target-block player-id exp)]
					(do
						(when (block-manip/can-break-block? block-manip/*block-manipulation* player-id world-id x y z)
							(block-manip/break-block! block-manip/*block-manipulation* player-id world-id x y z false))
						(let [focus (hold-focus player-id)]
							(ctx/update-context! ctx-id assoc :skill-state
													 {:skip-default-cooldown true
														:fired false
														:mode :holding
														:hold-ticks 0
														:held-block {:block-id block-id
																		 :from-world? true
																		 :world-id world-id
																		 :source-x x
																		 :source-y y
																		 :source-z z}
														:focus focus})
							(ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
													 {:mode :hold-start
														:focus focus
														:block-id block-id})))
					;; No target at all
					(ctx/update-context! ctx-id assoc :skill-state
											 {:skip-default-cooldown true
												:fired false
												:mode :no-target}))))
		(catch Exception e
			(log/warn "MagManip key-down failed:" (ex-message e)))))

; Original s_tick: just calls updateMoveTo() every tick.
; We track hold-ticks, update focus, and send FX to client every 2 ticks.
(defn mag-manip-on-key-tick
	[{:keys [player-id ctx-id]}]
	(try
		(when-let [ctx-data (ctx/get-context ctx-id)]
			(let [skill-state (:skill-state ctx-data)]
				(when (= :holding (:mode skill-state))
					(let [ticks (inc (int (or (:hold-ticks skill-state) 0)))
								focus (hold-focus player-id)]
						(ctx/update-context! ctx-id assoc-in [:skill-state :hold-ticks] ticks)
						(ctx/update-context! ctx-id assoc-in [:skill-state :focus] focus)
						(when (zero? (mod ticks 2))
							(ctx/ctx-send-to-client! ctx-id :mag-manip/fx-hold
														 {:mode :hold-loop
															:focus focus
															:block-id (get-in skill-state [:held-block :block-id])}))))))
		(catch Exception e
			(log/warn "MagManip key-tick failed:" (ex-message e)))))

; Original s_perform: check distsq < 25, then ctx.consume, then throw with speed.
; Entity damage is always 10 (hardcoded in MagManipEntityBlock constructor).
; Exp gain on throw: 0.005F (ctx.addSkillExp(0.005F))
(defn mag-manip-on-key-up
	[{:keys [player-id ctx-id cost-ok?]}]
	(try
		(when-let [ctx-data (ctx/get-context ctx-id)]
			(let [skill-state (:skill-state ctx-data)
						held-block (get skill-state :held-block)
						exp (get-skill-exp player-id)]
				(if-not (and (= :holding (:mode skill-state)) held-block)
					(ctx/update-context! ctx-id assoc :skill-state
															 (assoc skill-state :skip-default-cooldown true :fired false :mode :idle))
					;; Check distance: entity (at focus position) must be within 5 blocks of player
					(let [focus-pos (or (:focus skill-state) (hold-focus player-id))
								player-now (player-pos player-id)
								dist-sq (+ (Math/pow (- (:x player-now) (:x focus-pos)) 2.0)
													 (Math/pow (- (:y player-now) (:y focus-pos)) 2.0)
													 (Math/pow (- (:z player-now) (:z focus-pos)) 2.0))
								too-far? (>= dist-sq 25.0)]
						(if too-far?
							;; Out of range: restore block, no cost, no throw
							(do
								(restore-held-block! held-block)
								(ctx/update-context! ctx-id assoc :skill-state
																 (assoc skill-state :skip-default-cooldown true :fired false :mode :too-far)))
							(if-not cost-ok?
								(do
									(restore-held-block! held-block)
									(ctx/update-context! ctx-id assoc :skill-state
																		 (assoc skill-state :skip-default-cooldown true :fired false :mode :no-resource)))
								(let [world-id (player-world-id player-id)
											start focus-pos
											dir (or (look-dir player-id) {:x 0.0 :y 0.0 :z 1.0})
											hit (when raycast/*raycast*
														(raycast/raycast-combined raycast/*raycast*
																											world-id
																											(:x start) (:y start) (:z start)
																											(:x dir) (:y dir) (:z dir)
																											max-throw-range))
											end (if hit
														{:x (double (:x hit)) :y (double (:y hit)) :z (double (:z hit))}
														(v+ start (v* dir max-throw-range)))
											;; Original entity damage: always 10 (MagManipEntityBlock(player, 10))
											damage 10.0
											direct-hit? (and (= (:hit-type hit) :entity)
																			 (:uuid hit)
																			 entity-damage/*entity-damage*)
											_ (when direct-hit?
													(entity-damage/apply-direct-damage! entity-damage/*entity-damage*
																														 world-id
																														 (:uuid hit)
																														 damage
																														 :magic))
											_ (when (and (not direct-hit?) world-effects/*world-effects* entity-damage/*entity-damage*)
													(let [mid (v* (v+ start end) 0.5)
																radius (+ (* 0.5 (vlen (v- end start))) 2.0)
																entities (sort-by :uuid
																									(remove #(= (:uuid %) player-id)
																													(world-effects/find-entities-in-radius world-effects/*world-effects*
																																															 world-id
																																															 (:x mid) (:y mid) (:z mid)
																																															 radius)))]
														(when-let [target (first
																					 (filter (fn [{:keys [x y z]}]
																										 (let [{:keys [distance t]} (distance-to-segment {:x x :y y :z z} start end)]
																											 (and (<= distance throw-hit-radius)
																													 (<= 0.0 t 1.0))))
																									 entities))]
															(entity-damage/apply-direct-damage! entity-damage/*entity-damage*
																																 world-id
																																 (:uuid target)
																																 damage
																																 :magic))))
											_ (when (= (:hit-type hit) :block)
													(try-place-thrown-block! world-id end (:block-id held-block)))
											_ (ctx/ctx-send-to-client! ctx-id :mag-manip/fx-throw
																									 {:mode :throw
																										:start start
																										:end end
																										:hit-type (:hit-type hit)
																										:block-id (:block-id held-block)})]
									(apply-mag-manip-cooldown! player-id exp)
									;; Original: ctx.addSkillExp(0.005F)
									(add-mag-manip-exp! player-id 0.005)
									(ctx/update-context! ctx-id assoc :skill-state
																		 {:skip-default-cooldown true
																			:fired true
																			:mode :thrown
																			:held-block nil}))))))))
		(catch Exception e
			(log/warn "MagManip key-up failed:" (ex-message e)))))

(defn mag-manip-on-key-abort
	[{:keys [ctx-id]}]
	(try
		(when-let [ctx-data (ctx/get-context ctx-id)]
			(when-let [held (get-in ctx-data [:skill-state :held-block])]
				(restore-held-block! held))
			(ctx/update-context! ctx-id dissoc :skill-state))
		(catch Exception e
			(log/warn "MagManip key-abort failed:" (ex-message e)))))