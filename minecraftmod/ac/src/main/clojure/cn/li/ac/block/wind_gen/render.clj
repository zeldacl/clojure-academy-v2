(ns cn.li.ac.block.wind-gen.render
	"CLIENT-ONLY: Wind Generator block entity renderers."
	(:require [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
						[cn.li.mcmod.client.resources :as res]
						[cn.li.mcmod.client.obj :as obj]
						[cn.li.mcmod.client.render.tesr-api :as tesr-api]
						[cn.li.mcmod.client.render.multiblock-helper :as mb-helper]
						[cn.li.mcmod.client.render.pose :as pose]
						[cn.li.mcmod.client.render.buffer :as rb]
						[cn.li.mcmod.util.render :as render]
						[cn.li.mcmod.util.log :as log]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.be :as platform-be]))

(def ^:private wind-render-resource-lock (Object.))
(def ^:private ^:dynamic *base-model* nil)
(def ^:private ^:dynamic *main-model* nil)
(def ^:private ^:dynamic *fan-model* nil)
(def ^:private ^:dynamic *pillar-model* nil)
(def ^:private ^:dynamic *base-tex-normal* nil)
(def ^:private ^:dynamic *base-tex-disabled* nil)
(def ^:private ^:dynamic *main-tex* nil)
(def ^:private ^:dynamic *fan-tex* nil)
(def ^:private ^:dynamic *pillar-tex* nil)

(def ^:private base-model
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*base-model*
	                                    #(res/load-obj-model "windgen_base")))

(def ^:private main-model
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*main-model*
	                                    #(res/load-obj-model "windgen_main")))

(def ^:private fan-model
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*fan-model*
	                                    #(res/load-obj-model "windgen_fan")))

(def ^:private pillar-model
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*pillar-model*
	                                    #(res/load-obj-model "windgen_pillar")))

(def ^:private base-tex-normal
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*base-tex-normal*
	                                    #(res/texture-location "models/windgen_base")))

(def ^:private base-tex-disabled
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*base-tex-disabled*
	                                    #(res/texture-location "models/windgen_base_disabled")))

(def ^:private main-tex
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*main-tex*
	                                    #(res/texture-location "models/windgen_main")))

(def ^:private fan-tex
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*fan-tex*
	                                    #(res/texture-location "models/windgen_fan")))

(def ^:private pillar-tex
	(machine-render-runtime/lazy-resource wind-render-resource-lock #'*pillar-tex*
	                                    #(res/texture-location "models/windgen_pillar")))

(defonce ^:private fan-cache
	(machine-render-runtime/create-cache-runtime :fan-rot-cache))

(def ^:dynamic *wind-gen-render-runtime* (:runtime fan-cache))

(defn- fan-rot-cache-atom
	[]
	(machine-render-runtime/cache-atom *wind-gen-render-runtime* :fan-rot-cache))

(defn fan-rot-cache-snapshot
	[]
	(machine-render-runtime/cache-snapshot *wind-gen-render-runtime* :fan-rot-cache))

(defn clear-fan-rot-cache!
	[]
	((:clear! fan-cache)))

(defn reset-fan-rot-cache-for-test!
	([]
	 ((:reset-for-test! fan-cache)))
	([cache]
	 ((:reset-for-test! fan-cache) cache)))

(defn- tile-key [tile]
	(let [p (pos/position-get-block-pos tile)]
		[(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)]))

(defn- update-fan-rotation!
	[tile speed-deg-per-sec]
	(let [k (tile-key tile)
				now (render/get-render-time)
				prev (get (fan-rot-cache-snapshot) k {:t now :rot 0.0})
				dt (max 0.0 (- now (:t prev)))
				next-rot (+ (:rot prev) (* speed-deg-per-sec dt))]
		(swap! (fan-rot-cache-atom) assoc k {:t now :rot next-rot})
		next-rot))

(defn- render-base-at-origin
	[tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [state (or (platform-be/get-custom-state tile) {})
				complete? (= "COMPLETE" (str (get state :status "BASE_ONLY")))
				tex (if complete? (base-tex-normal) (base-tex-disabled))
				vc (rb/get-solid-buffer buffer-source tex)]
		(binding [obj/*skip-flat-bottom-plane* true
							obj/*bottom-plane-epsilon* 0.0008]
			(obj/render-all! (base-model) pose-stack vc packed-light packed-overlay))))

(defn- render-main-at-origin
	[tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [state (or (platform-be/get-custom-state tile) {})
				fan-installed? (boolean (get state :fan-installed false))
				no-obstacle? (boolean (get state :no-obstacle false))
				complete? (boolean (get state :complete false))]
		(let [vc-main (rb/get-solid-buffer buffer-source (main-tex))]
			(binding [obj/*skip-flat-bottom-plane* true
								obj/*bottom-plane-epsilon* 0.0008]
				(obj/render-all! (main-model) pose-stack vc-main packed-light packed-overlay)))

		(when (and fan-installed? no-obstacle?)
			(pose/push-pose pose-stack)
			(try
				(pose/translate pose-stack 0.0 0.5 0.82)
				(pose/apply-z-rotation pose-stack (- (double (update-fan-rotation! tile (if complete? 60.0 0.0)))))
				(let [vc-fan (rb/get-solid-buffer buffer-source (fan-tex))]
					(binding [obj/*skip-flat-bottom-plane* true
										obj/*bottom-plane-epsilon* 0.0008]
						(obj/render-all! (fan-model) pose-stack vc-fan packed-light packed-overlay)))
				(finally
					(pose/pop-pose pose-stack))))))

(defn- render-pillar-at-origin
	[_tile _partial-ticks pose-stack buffer-source packed-light packed-overlay]
	(let [vc (rb/get-solid-buffer buffer-source (pillar-tex))]
		(pose/push-pose pose-stack)
		(try
			;; Single-block TESR origin is block corner; move OBJ to block center.
			(pose/translate pose-stack 0.5 0.0 0.5)
			(binding [obj/*skip-flat-bottom-plane* true
								obj/*bottom-plane-epsilon* 0.0008]
				(obj/render-all! (pillar-model) pose-stack vc packed-light packed-overlay))
			(finally
				(pose/pop-pose pose-stack)))))

(defn register!
	[]
	(let [base-renderer
					{:render-tile (fn [tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
					                (mb-helper/render-multiblock-tesr
					                 tile-entity
					                 render-base-at-origin
					                 partial-ticks pose-stack buffer-source packed-light packed-overlay))}

					main-renderer
					{:render-tile (fn [tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
					                (mb-helper/render-multiblock-tesr
					                 tile-entity
					                 render-main-at-origin
					                 partial-ticks pose-stack buffer-source packed-light packed-overlay))}

					pillar-renderer
					{:render-tile (fn [tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
					                (render-pillar-at-origin
					                 tile-entity
					                 partial-ticks pose-stack buffer-source packed-light packed-overlay))}]

		(tesr-api/register-scripted-tile-renderer! "wind-gen-base" base-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-base-part" base-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-main" main-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-main-part" main-renderer)
		(tesr-api/register-scripted-tile-renderer! "wind-gen-pillar" pillar-renderer)))

(defn init!
	[]
	(machine-render-runtime/register-client-renderer-init! 'cn.li.ac.block.wind-gen.render/register!))