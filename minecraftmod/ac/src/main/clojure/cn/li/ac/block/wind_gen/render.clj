(ns cn.li.ac.block.wind-gen.render
	"CLIENT-ONLY: Wind Generator block entity renderers."
	(:require [cn.li.mcmod.client.resources :as res]
						[cn.li.mcmod.client.obj :as obj]
						[cn.li.mcmod.client.render.tesr-api :as tesr-api]
						[cn.li.mcmod.client.render.multiblock-helper :as mb-helper]
						[cn.li.mcmod.client.render.pose :as pose]
						[cn.li.mcmod.client.render.buffer :as rb]
						[cn.li.mcmod.util.render :as render]
						[cn.li.mcmod.util.log :as log]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.be :as platform-be]))

(defonce base-model (delay (res/load-obj-model "windgen_base")))
(defonce main-model (delay (res/load-obj-model "windgen_main")))
(defonce fan-model (delay (res/load-obj-model "windgen_fan")))
(defonce pillar-model (delay (res/load-obj-model "windgen_pillar")))

(defonce base-tex-normal (delay (res/texture-location "models/windgen_base")))
(defonce base-tex-disabled (delay (res/texture-location "models/windgen_base_disabled")))
(defonce main-tex (delay (res/texture-location "models/windgen_main")))
(defonce fan-tex (delay (res/texture-location "models/windgen_fan")))
(defonce pillar-tex (delay (res/texture-location "models/windgen_pillar")))

(defonce ^:private fan-rot-cache (atom {}))

(defn- tile-key [tile]
	(let [p (pos/position-get-block-pos tile)]
		[(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)]))

(defn- update-fan-rotation!
	[tile speed-deg-per-sec]
	(let [k (tile-key tile)
				now (render/get-render-time)
				prev (get @fan-rot-cache k {:t now :rot 0.0})
				dt (max 0.0 (- now (:t prev)))
				next-rot (+ (:rot prev) (* speed-deg-per-sec dt))]
		(swap! fan-rot-cache assoc k {:t now :rot next-rot})
		next-rot))

(defn- render-base-at-origin
	[tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [state (or (platform-be/get-custom-state tile) {})
				complete? (= "COMPLETE" (str (get state :status "BASE_ONLY")))
				tex (if complete? @base-tex-normal @base-tex-disabled)
				vc (rb/get-solid-buffer buffer-source tex)]
		(binding [obj/*skip-flat-bottom-plane* true
							obj/*bottom-plane-epsilon* 0.0008]
			(obj/render-all! @base-model pose-stack vc packed-light packed-overlay))))

(defn- render-main-at-origin
	[tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [state (or (platform-be/get-custom-state tile) {})
				fan-installed? (boolean (get state :fan-installed false))
				no-obstacle? (boolean (get state :no-obstacle false))
				complete? (boolean (get state :complete false))]
		(let [vc-main (rb/get-solid-buffer buffer-source @main-tex)]
			(binding [obj/*skip-flat-bottom-plane* true
								obj/*bottom-plane-epsilon* 0.0008]
				(obj/render-all! @main-model pose-stack vc-main packed-light packed-overlay)))

		(when (and fan-installed? no-obstacle?)
			(pose/push-pose pose-stack)
			(try
				(pose/translate pose-stack 0.0 0.5 0.82)
				(pose/apply-z-rotation pose-stack (- (double (update-fan-rotation! tile (if complete? 60.0 0.0)))))
				(let [vc-fan (rb/get-solid-buffer buffer-source @fan-tex)]
					(binding [obj/*skip-flat-bottom-plane* true
										obj/*bottom-plane-epsilon* 0.0008]
						(obj/render-all! @fan-model pose-stack vc-fan packed-light packed-overlay)))
				(finally
					(pose/pop-pose pose-stack))))))

(defn- render-pillar-at-origin
	[_tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [vc (rb/get-solid-buffer buffer-source @pillar-tex)]
		(pose/push-pose pose-stack)
		(try
			;; Single-block TESR origin is block corner; move OBJ to block center.
			(pose/translate pose-stack 0.5 0.0 0.5)
			(binding [obj/*skip-flat-bottom-plane* true
								obj/*bottom-plane-epsilon* 0.0008]
				(obj/render-all! @pillar-model pose-stack vc packed-light packed-overlay))
			(finally
				(pose/pop-pose pose-stack)))))

(defn register!
	[]
	(let [base-renderer
				(reify tesr-api/ITileEntityRenderer
					(render-tile [_ tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
						(mb-helper/render-multiblock-tesr
						 tile-entity
						 render-base-at-origin
						 partial-ticks pose-stack buffer-source packed-light packed-overlay)))

				main-renderer
				(reify tesr-api/ITileEntityRenderer
					(render-tile [_ tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
						(mb-helper/render-multiblock-tesr
						 tile-entity
						 render-main-at-origin
						 partial-ticks pose-stack buffer-source packed-light packed-overlay)))

				pillar-renderer
				(reify tesr-api/ITileEntityRenderer
					(render-tile [_ tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
						(render-pillar-at-origin
						 tile-entity
						 partial-ticks pose-stack buffer-source packed-light packed-overlay)))]

		(tesr-api/register-scripted-tile-renderer! "wind-gen-base" base-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-base-part" base-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-main" main-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-main-part" main-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-pillar" pillar-renderer)))

(defonce ^:private wind-renderer-installed? (atom false))

(defn init!
	[]
	(when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
		(when (compare-and-set! wind-renderer-installed? false true)
			(register-fn register!)
			(log/info "Registered wind generator renderers"))))