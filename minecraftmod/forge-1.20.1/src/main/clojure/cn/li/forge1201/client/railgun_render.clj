(ns cn.li.forge1201.client.railgun-render
	"CLIENT-ONLY high-fidelity railgun rendering layer.

	- Beam body uses textured ribbon quads (EntityRailgunFX-like).
	- Beam width attenuates with distance.
	- Hit endpoint flash is rendered as short-lived billboard quads."
	(:require [cn.li.forge1201.client.ability-runtime :as client-runtime]
						[cn.li.mcmod.util.log :as log])
	(:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
					 [net.minecraft.client Minecraft]
					 [net.minecraft.client.renderer MultiBufferSource$BufferSource RenderType]
					 [net.minecraft.client.renderer.texture OverlayTexture]
					 [net.minecraft.resources ResourceLocation]
					 [net.minecraft.world.phys Vec3]
					 [net.minecraftforge.client.event RenderLevelStageEvent]
					 [net.minecraftforge.common MinecraftForge]
					 [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
					 [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private beam-effects (atom []))
(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private render-listener-registered? (atom false))

(def ^:private beam-life-ticks 12)
(def ^:private full-bright-uv2 15728880)
(def ^:private beam-texture
	(ResourceLocation. "minecraft" "textures/entity/beacon_beam.png"))

(defn enqueue-shot-fx!
	[{:keys [mode start end hit-distance]}]
	(when (and start end)
		(swap! beam-effects conj {:start start
																	:end end
																	:mode (or mode :block-hit)
																	:hit-distance (double (or hit-distance 18.0))
																	:ttl beam-life-ticks
																	:max-ttl beam-life-ticks})))

(defn- tick-effects!
	[]
	(swap! beam-effects
				 (fn [xs]
					 (->> xs
								(map (fn [fx] (update fx :ttl dec)))
								(filter (fn [fx] (pos? (long (:ttl fx)))))
								vec))))

(defn- render-stage-eligible?
	[^RenderLevelStageEvent evt]
	(let [stage-name (str (.getStage evt))]
		(or (.contains stage-name "AFTER_PARTICLES")
				(.contains stage-name "AFTER_TRANSLUCENT"))))

(defn- v+
	[a b]
	{:x (+ (double (:x a)) (double (:x b)))
	 :y (+ (double (:y a)) (double (:y b)))
	 :z (+ (double (:z a)) (double (:z b)))})

(defn- v-
	[a b]
	{:x (- (double (:x a)) (double (:x b)))
	 :y (- (double (:y a)) (double (:y b)))
	 :z (- (double (:z a)) (double (:z b)))})

(defn- v*
	[a s]
	{:x (* (double (:x a)) (double s))
	 :y (* (double (:y a)) (double s))
	 :z (* (double (:z a)) (double s))})

(defn- vlen
	[v]
	(Math/sqrt (+ (* (:x v) (:x v))
							(* (:y v) (:y v))
							(* (:z v) (:z v)))))

(defn- vnormalize
	[v]
	(let [len (max 1.0e-6 (vlen v))]
		(v* v (/ 1.0 len))))

(defn- vcross
	[a b]
	{:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
	 :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
	 :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- midpoint
	[a b]
	(v* (v+ a b) 0.5))

(defn- emit-line-vertex!
	[^VertexConsumer vc mat x y z r g b a]
	(-> vc
			(.vertex mat (float x) (float y) (float z))
			(.color (int r) (int g) (int b) (int a))
			(.endVertex)))

(defn- emit-line!
	[^VertexConsumer vc mat p1 p2 color]
	(let [{:keys [r g b a]} color]
		(emit-line-vertex! vc mat (:x p1) (:y p1) (:z p1) r g b a)
		(emit-line-vertex! vc mat (:x p2) (:y p2) (:z p2) r g b a)))

(defn- emit-quad-vertex!
	[^VertexConsumer vc mat p u v color]
	(let [{:keys [r g b a]} color]
		(-> vc
				(.vertex mat (float (:x p)) (float (:y p)) (float (:z p)))
				(.color (int r) (int g) (int b) (int a))
				(.uv (float u) (float v))
				(.overlayCoords (int OverlayTexture/NO_OVERLAY))
				(.uv2 (int full-bright-uv2))
				(.normal 0.0 1.0 0.0)
				(.endVertex))))

(defn- emit-quad!
	[^VertexConsumer vc mat p0 p1 p2 p3 u0 u1 v0 v1 color]
	;; two triangles: (p0 p1 p2) + (p2 p3 p0)
	(emit-quad-vertex! vc mat p0 u0 v0 color)
	(emit-quad-vertex! vc mat p1 u1 v0 color)
	(emit-quad-vertex! vc mat p2 u1 v1 color)
	(emit-quad-vertex! vc mat p2 u1 v1 color)
	(emit-quad-vertex! vc mat p3 u0 v1 color)
	(emit-quad-vertex! vc mat p0 u0 v0 color))

(defn- with-alpha
	[color a]
	(assoc color :a (int (max 0 (min 255 a)))))

(defn- clamp01
	[v]
	(max 0.0 (min 1.0 (double v))))

(defn- temporal-profile
	[ttl max-ttl]
	(let [life (clamp01 (/ (double ttl) (double (max 1 max-ttl))))
				age (- 1.0 life)
				head (Math/exp (* -12.0 age))
				tail (Math/pow life 2.05)
				glow (* tail (+ 1.0 (* 0.95 head)))
				scroll (+ 1.0 (* 1.6 head))]
		{:life life :age age :head head :tail tail :glow glow :scroll scroll}))

(defn- beam-palette
	[mode]
	(case mode
		:reflect {:outer {:r 236 :g 170 :b 93}
						:inner {:r 241 :g 240 :b 222}
						:flash {:r 255 :g 228 :b 176}}
		:perform {:outer {:r 236 :g 170 :b 93}
					 :inner {:r 241 :g 240 :b 222}
					 :flash {:r 255 :g 228 :b 176}}
		:entity-hit {:outer {:r 236 :g 170 :b 93}
							 :inner {:r 241 :g 240 :b 222}
							 :flash {:r 255 :g 228 :b 176}}
		:block-hit {:outer {:r 236 :g 170 :b 93}
							:inner {:r 241 :g 240 :b 222}
							:flash {:r 255 :g 228 :b 176}}
		{:outer {:r 236 :g 170 :b 93}
		 :inner {:r 241 :g 240 :b 222}
		 :flash {:r 255 :g 228 :b 176}}))

(defn- ring-noise
	[idx age]
	;; Deterministic pseudo-noise in [-1, 1] for stable crack offsets.
	(let [v (+ (* 12.9898 (double idx)) (* 4.1414 (double age)))
			s (Math/sin v)]
		(- (* 2.0 s) (Math/cos (* 0.37 v)))))

(defn- sector-glow
	[theta age mode]
	;; Non-uniform sector blinking to avoid perfectly round constant brightness.
	(let [phase (if (= mode :block-hit) 0.8 0.2)
				a (+ 0.5 (* 0.5 (Math/sin (+ (* 3.0 theta) (* 4.6 age) phase))))
				b (+ 0.5 (* 0.5 (Math/sin (+ (* 7.0 theta) (* -3.4 age) 1.7))))
				gate (if (> a 0.46) 1.0 0.28)]
		(max 0.14 (* gate (+ 0.25 (* 0.75 b))))))

(defn- beam-width-by-distance
	[distance]
	(let [d (max 0.0 (double distance))
				base (- 0.16 (* 0.0018 d))]
		(max 0.048 (min 0.16 base))))

(defn- beam-right-axis
	[start end cam-pos]
	(let [dir (vnormalize (v- end start))
				mid (midpoint start end)
				to-cam (vnormalize (v- cam-pos mid))
				raw (vcross dir to-cam)
				len (vlen raw)]
		(if (> len 1.0e-5)
			(vnormalize raw)
			(let [fallback (vcross dir {:x 0.0 :y 1.0 :z 0.0})]
				(if (> (vlen fallback) 1.0e-5)
					(vnormalize fallback)
					{:x 1.0 :y 0.0 :z 0.0})))))

(defn- draw-beam-ribbon!
	[^VertexConsumer vc mat cam-pos {:keys [mode start end ttl max-ttl hit-distance]}]
	(let [dist (double (or hit-distance (vlen (v- end start))))
				right (beam-right-axis start end cam-pos)
				{:keys [age glow scroll head]} (temporal-profile ttl max-ttl)
				width-boost (+ 1.0 (* 0.22 head))
				w0 (* (beam-width-by-distance dist) width-boost)
				w1 (* w0 0.58)
				age-ticks (* age (double max-ttl))
				palette (beam-palette mode)
				alpha-main (int (min 255 (+ 20 (* 210 glow))))
				alpha-core (int (min 255 (+ 42 (* 230 glow))))
				u-main (* 0.22 age-ticks scroll)
				u-core (* 0.52 age-ticks scroll)
				r0 (v* right w0)
				r1 (v* right w1)
				p0 (v+ start r0)
				p1 (v- start r0)
				p2 (v- end r1)
				p3 (v+ end r1)
				core-r0 (v* right (* w0 0.42))
				core-r1 (v* right (* w1 0.32))
				c0 (v+ start core-r0)
				c1 (v- start core-r0)
				c2 (v- end core-r1)
				c3 (v+ end core-r1)]
		(emit-quad! vc mat p0 p1 p2 p3 u-main (+ u-main 1.0) 0.0 1.0
						 (with-alpha (:outer palette) alpha-main))
		(emit-quad! vc mat c0 c1 c2 c3 (+ u-core 0.12) (+ u-core 0.88) 0.0 1.0
						 (with-alpha (:inner palette) alpha-core))))

(defn- beam-arc-color
	[mode alpha]
	(case mode
		:reflect {:r 255 :g 224 :b 166 :a alpha}
		{:r 165 :g 230 :b 255 :a alpha}))

(defn- draw-beam-sub-arcs!
	[^VertexConsumer vc mat {:keys [mode start end ttl max-ttl hit-distance]}]
	(let [dist (max 0.8 (double (or hit-distance (vlen (v- end start)))))
				dir (vnormalize (v- end start))
				{:keys [age glow head]} (temporal-profile ttl max-ttl)
				axis-a (let [raw (vcross dir {:x 0.0 :y 1.0 :z 0.0})]
						 (if (> (vlen raw) 1.0e-6) (vnormalize raw) {:x 1.0 :y 0.0 :z 0.0}))
				axis-b (vnormalize (vcross dir axis-a))
				segments (int (max 8 (min 36 (Math/round (* 1.35 dist)))))
				age-ticks (* age (double max-ttl))
				base-amp (* (max 0.04 (min 0.16 (- 0.16 (* 0.0015 dist))))
								(+ (* 0.5 glow) (* 0.4 head) 0.28))
				arcs 3]
		(dotimes [arc arcs]
			(let [phase (+ (* arc 1.9) (* age-ticks 0.52))
					freq (+ 8.0 (* arc 1.7))
					alpha (int (min 255 (* (+ 20 (* 155 glow) (* 65 head)) (- 1.0 (* arc 0.16)))))
					color (beam-arc-color mode alpha)]
				(loop [i 0 prev nil]
					(when (<= i segments)
						(let [t (/ (double i) (double segments))
								base (v+ start (v* dir (* dist t)))
								swing (+ (* 0.72 (Math/sin (+ phase (* freq t))))
											 (* 0.38 (Math/sin (+ (* 2.4 phase) (* (+ 4.0 freq) t)))))
								sway (+ (* 0.63 (Math/cos (+ (* 1.3 phase) (* (+ 2.0 freq) t))))
										(* 0.27 (Math/sin (+ (* 3.1 phase) (* (+ 6.0 freq) t)))))
								amp (* base-amp (+ 0.25 (* 0.75 (Math/sin (* Math/PI t)))))
								offset (v+ (v* axis-a (* amp swing))
											  (v* axis-b (* amp sway)))
								cur (v+ base offset)]
							(when prev
								(emit-line! vc mat prev cur color))
							(recur (inc i) cur))))))))

(defn- draw-hit-flash!
	[^VertexConsumer vc mat cam-pos {:keys [mode end ttl max-ttl]}]
	(when (>= ttl (- max-ttl 4))
		(let [{:keys [life age head]} (temporal-profile ttl max-ttl)
				impact (* 1.15 head)
				decay (Math/pow life 2.6)
				intensity (max 0.0 (+ impact (* 0.28 decay)))
				size (+ 0.1 (* 0.26 intensity))
				palette (beam-palette mode)
				to-cam (vnormalize (v- cam-pos end))
				axis-x (let [raw (vcross {:x 0.0 :y 1.0 :z 0.0} to-cam)]
							 (if (> (vlen raw) 1.0e-6) (vnormalize raw) {:x 1.0 :y 0.0 :z 0.0}))
				axis-y (vnormalize (vcross to-cam axis-x))
				axis-x2 (vnormalize (v+ axis-x axis-y))
				axis-y2 (vnormalize (v- axis-y axis-x))
				alpha (int (min 255 (+ 18 (* 360 intensity))))
				x0 (v* axis-x size)
				y0 (v* axis-y size)
				x1 (v* axis-x2 (* size 0.86))
				y1 (v* axis-y2 (* size 0.86))
				x2 (v* axis-x (* size 0.56))
				y2 (v* axis-y (* size 0.56))
				p0 (v+ (v+ end x0) y0)
				p1 (v+ (v- end x0) y0)
				p2 (v- (v- end x0) y0)
				p3 (v- (v+ end x0) y0)
				q0 (v+ (v+ end x1) y1)
				q1 (v+ (v- end x1) y1)
				q2 (v- (v- end x1) y1)
				q3 (v- (v+ end x1) y1)
				r0 (v+ (v+ end x2) y2)
				r1 (v+ (v- end x2) y2)
				r2 (v- (v- end x2) y2)
				r3 (v- (v+ end x2) y2)
				color (with-alpha (:flash palette) alpha)
				core-color (with-alpha {:r 255 :g 246 :b 220} (int (min 255 (+ 35 (* 280 intensity)))))]
			(emit-quad! vc mat p0 p1 p2 p3 0.0 1.0 0.0 1.0 color)
			(emit-quad! vc mat q0 q1 q2 q3 0.0 1.0 0.0 1.0 color)
			(emit-quad! vc mat r0 r1 r2 r3 0.0 1.0 0.0 1.0 core-color))))

(defn- draw-hit-expansion-ring!
	[^VertexConsumer vc mat cam-pos {:keys [mode end ttl max-ttl]}]
	(let [age (- (double max-ttl) (double ttl))
			expand (if (<= age 1.0)
							(+ 0.15 (* 0.85 age))
							1.0)
			fade-tail (if (<= age 1.0)
							1.0
							(max 0.0 (- 1.0 (* 0.7 (- age 1.0)))))
			alpha (int (* 220.0 fade-tail))]
		(when (> alpha 8)
			(let [palette (beam-palette mode)
					radius-outer (* (+ 0.075 (* 0.24 expand)) (+ 0.75 (* 0.25 fade-tail)))
					radius-inner (* radius-outer 0.62)
					to-cam (vnormalize (v- cam-pos end))
					axis-x (let [raw (vcross {:x 0.0 :y 1.0 :z 0.0} to-cam)]
								 (if (> (vlen raw) 1.0e-6) (vnormalize raw) {:x 1.0 :y 0.0 :z 0.0}))
					axis-y (vnormalize (vcross to-cam axis-x))
					segments 18
					outer-color (with-alpha
										 (if (= mode :block-hit)
											 {:r 165 :g 248 :b 236}
											 (:flash palette))
										 alpha)
					inner-color (with-alpha {:r 246 :g 252 :b 255} (int (* 0.9 alpha)))
					crack-fade (max 0.0 (- 1.0 (* 0.55 age)))]
				(dotimes [i segments]
					(let [n0 (ring-noise i age)
							n1 (ring-noise (inc i) age)
							theta0 (/ (* 2.0 Math/PI i) segments)
							theta1 (/ (* 2.0 Math/PI (inc i)) segments)
							sector (max 0.12 (* 0.5 (+ (sector-glow theta0 age mode)
																					(sector-glow theta1 age mode))))
							crack0 (if (= mode :block-hit)
										 (* crack-fade 0.03 (+ 0.4 (Math/abs n0)))
										 0.0)
							crack1 (if (= mode :block-hit)
										 (* crack-fade 0.03 (+ 0.4 (Math/abs n1)))
										 0.0)
							t0 (+ theta0 (* 0.04 n0 crack-fade))
							t1 (+ theta1 (* 0.04 n1 crack-fade))
							r0o (+ radius-outer crack0)
							r1o (+ radius-outer crack1)
							r0i (* radius-inner (+ 1.0 (* 0.12 n0 crack-fade)))
							r1i (* radius-inner (+ 1.0 (* 0.12 n1 crack-fade)))
							o0 (v+ end (v+ (v* axis-x (* r0o (Math/cos t0)))
													(v* axis-y (* r0o (Math/sin t0)))))
							o1 (v+ end (v+ (v* axis-x (* r1o (Math/cos t1)))
													(v* axis-y (* r1o (Math/sin t1)))))
							i0 (v+ end (v+ (v* axis-x (* r0i (Math/cos t0)))
													(v* axis-y (* r0i (Math/sin t0)))))
							i1 (v+ end (v+ (v* axis-x (* r1i (Math/cos t1)))
													(v* axis-y (* r1i (Math/sin t1)))))
							outer-color* (with-alpha outer-color (* (:a outer-color) sector))
							inner-color* (with-alpha inner-color (* (:a inner-color) (+ 0.1 (* 0.9 sector))))]
						(emit-line! vc mat o0 o1 outer-color*)
						(emit-line! vc mat i0 i1 inner-color*)))))))

(defn- draw-block-hit-sparks!
	[^VertexConsumer vc mat {:keys [mode start end ttl max-ttl]}]
	(when (and (= mode :block-hit) start end)
		(let [age (- (double max-ttl) (double ttl))
				fade (if (<= age 0.6)
							1.0
							(max 0.0 (- 1.0 (* 0.7 (- age 0.6)))))
				alpha (int (* 185.0 fade))]
			(when (> alpha 10)
				(let [normal (vnormalize (v- end start))
						tan-a (let [raw (vcross normal {:x 0.0 :y 1.0 :z 0.0})]
									(if (> (vlen raw) 1.0e-6) (vnormalize raw) {:x 1.0 :y 0.0 :z 0.0}))
						tan-b (vnormalize (vcross tan-a normal))
						spark-color (with-alpha {:r 188 :g 252 :b 238} alpha)
						sparks 13]
					(dotimes [i sparks]
						(let [n0 (ring-noise (+ i 17) age)
								n1 (ring-noise (+ i 43) (+ age 0.37))
								origin (v+ end
											 (v+ (v* tan-a (* 0.014 n0))
													 (v* tan-b (* 0.014 n1))))
								dir (vnormalize
									  (v+
										(v* normal (+ 0.72 (* 0.2 (Math/abs n0))))
										(v+
											(v* tan-a (* 0.45 n0))
											(v* tan-b (* 0.45 n1)))))
								len (* (+ 0.08 (* 0.12 (Math/abs n1))) fade)
								endp (v+ origin (v* dir len))]
							(emit-line! vc mat origin endp spark-color))))))))

(defn- local-player-uuid
	[]
	(when-let [^Minecraft mc (Minecraft/getInstance)]
		(when-let [player (.player mc)]
			(str (.getUUID player)))))

(defn- hand-center-pos
	[player]
	(let [^Vec3 look (.getLookAngle player)
				yaw-rad (Math/toRadians (double (.getYRot player)))
				right-x (Math/cos yaw-rad)
				right-z (Math/sin yaw-rad)
				base-x (.getX player)
				base-y (.getEyeY player)
				base-z (.getZ player)]
		{:x (+ base-x (* (.-x look) 0.35) (* right-x 0.22))
		 :y (+ base-y -0.22 (* (.-y look) 0.06))
		 :z (+ base-z (* (.-z look) 0.35) (* right-z 0.22))}))

(defn- draw-charge-hand-effect!
	[^VertexConsumer vc mat center charge-ratio coin-active? tick]
	(let [ratio (max 0.0 (min 1.0 (double charge-ratio)))
				core-radius (+ 0.06 (* 0.08 ratio))
				ring-radius (+ 0.11 (* 0.1 ratio))
				pulse (+ 0.02 (* 0.02 (Math/sin (* 0.25 tick))))
				points 14
				alpha (if coin-active? 210 170)]
		(dotimes [i points]
			(let [t0 (/ (* 2.0 Math/PI i) points)
						t1 (/ (* 2.0 Math/PI (inc i)) points)
						x0 (+ (:x center) (* (+ ring-radius pulse) (Math/cos t0)))
						y0 (+ (:y center) (* 0.45 core-radius) (Math/sin (* 2.0 t0)))
						z0 (+ (:z center) (* (+ ring-radius pulse) (Math/sin t0)))
						x1 (+ (:x center) (* (+ ring-radius pulse) (Math/cos t1)))
						y1 (+ (:y center) (* 0.45 core-radius) (Math/sin (* 2.0 t1)))
						z1 (+ (:z center) (* (+ ring-radius pulse) (Math/sin t1)))
						spoke-x (+ (:x center) (* core-radius (Math/cos t0)))
						spoke-y (+ (:y center) (* 0.4 core-radius))
						spoke-z (+ (:z center) (* core-radius (Math/sin t0)))]
				(emit-line! vc mat {:x x0 :y y0 :z z0} {:x x1 :y y1 :z z1}
										{:r 120 :g 220 :b 255 :a alpha})
				(emit-line! vc mat center {:x spoke-x :y spoke-y :z spoke-z}
										{:r 90 :g 190 :b 255 :a 120})))))

(defn- render-railgun-layer!
	[^RenderLevelStageEvent evt]
	(when (render-stage-eligible? evt)
		(when-let [^Minecraft mc (Minecraft/getInstance)]
			(when-let [player (.player mc)]
				(let [player-uuid (local-player-uuid)
							charge-state (when player-uuid (client-runtime/railgun-charge-visual-state player-uuid))
							beams @beam-effects]
					(when (or (seq beams) (:active? charge-state))
						(let [^PoseStack pose-stack (.getPoseStack evt)
									camera (.getMainCamera (.gameRenderer mc))
									cam-vec (.getPosition camera)
									cam-pos {:x (.-x cam-vec) :y (.-y cam-vec) :z (.-z cam-vec)}
									^MultiBufferSource$BufferSource buffer-source (.bufferSource (.renderBuffers mc))
									^VertexConsumer line-vc (.getBuffer buffer-source (RenderType/lines))
									^VertexConsumer beam-vc (.getBuffer buffer-source (RenderType/entityTranslucent beam-texture))
									tick (.getGameTime (.level player))]
							(.pushPose pose-stack)
							(.translate pose-stack (- (:x cam-pos)) (- (:y cam-pos)) (- (:z cam-pos)))
							(let [mat (.pose (.last pose-stack))]
								(doseq [beam beams]
									(draw-beam-ribbon! beam-vc mat cam-pos beam)
									(draw-beam-sub-arcs! line-vc mat beam)
									(draw-hit-flash! beam-vc mat cam-pos beam)
									(draw-hit-expansion-ring! line-vc mat cam-pos beam)
									(draw-block-hit-sparks! line-vc mat beam))
								(when (:active? charge-state)
									(draw-charge-hand-effect! line-vc
																 mat
																 (hand-center-pos player)
																 (:charge-ratio charge-state)
																 (:coin-active? charge-state)
																 tick)))
							(.popPose pose-stack)
							(.endBatch buffer-source))))))))

(defn- on-client-tick
	[^TickEvent$ClientTickEvent evt]
	(when (= TickEvent$Phase/END (.phase evt))
		(tick-effects!)))

(defn- on-render-level-stage
	[^RenderLevelStageEvent evt]
	(try
		(render-railgun-layer! evt)
		(catch Exception e
			(log/error "Railgun layer render failed" e))))

(defn init!
	[]
	(when (compare-and-set! tick-listener-registered? false true)
		(.addListener (MinecraftForge/EVENT_BUS)
									EventPriority/NORMAL false TickEvent$ClientTickEvent
									(reify java.util.function.Consumer
										(accept [_ evt] (on-client-tick evt)))))
	(when (compare-and-set! render-listener-registered? false true)
		(.addListener (MinecraftForge/EVENT_BUS)
									EventPriority/NORMAL false RenderLevelStageEvent
									(reify java.util.function.Consumer
										(accept [_ evt] (on-render-level-stage evt)))))
	(log/info "Railgun render layer initialized"))