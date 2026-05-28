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

(def ^:private wind-render-resource-lock
	(Object.))

(def ^:private ^:dynamic *wind-render-resources*
	{:base-model nil
	 :main-model nil
	 :fan-model nil
	 :pillar-model nil
	 :base-tex-normal nil
	 :base-tex-disabled nil
	 :main-tex nil
	 :fan-tex nil
	 :pillar-tex nil})

(defn- wind-resource
	[k loader]
	(or (get (var-get #'*wind-render-resources*) k)
			(locking wind-render-resource-lock
				(or (get (var-get #'*wind-render-resources*) k)
						(let [v (loader)]
							(alter-var-root #'*wind-render-resources* assoc k v)
							v)))))

(defn- base-model []
	(wind-resource :base-model #(res/load-obj-model "windgen_base")))

(defn- main-model []
	(wind-resource :main-model #(res/load-obj-model "windgen_main")))

(defn- fan-model []
	(wind-resource :fan-model #(res/load-obj-model "windgen_fan")))

(defn- pillar-model []
	(wind-resource :pillar-model #(res/load-obj-model "windgen_pillar")))

(defn- base-tex-normal []
	(wind-resource :base-tex-normal #(res/texture-location "models/windgen_base")))

(defn- base-tex-disabled []
	(wind-resource :base-tex-disabled #(res/texture-location "models/windgen_base_disabled")))

(defn- main-tex []
	(wind-resource :main-tex #(res/texture-location "models/windgen_main")))

(defn- fan-tex []
	(wind-resource :fan-tex #(res/texture-location "models/windgen_fan")))

(defn- pillar-tex []
	(wind-resource :pillar-tex #(res/texture-location "models/windgen_pillar")))

(defn create-wind-gen-render-runtime
	([]
	 (create-wind-gen-render-runtime {}))
	([initial-cache]
	 {:fan-rot-cache (atom initial-cache)}))

(defonce ^:private installed-wind-gen-render-runtime
	(create-wind-gen-render-runtime))

(defonce ^:private wind-gen-render-runtime-override* (atom nil))

(defn current-wind-gen-render-runtime
  []
  (or @wind-gen-render-runtime-override*
      installed-wind-gen-render-runtime))

(defmacro with-wind-gen-render-runtime
  [runtime & body]
  `(let [prev-override# @wind-gen-render-runtime-override*]
     (try
       (reset! wind-gen-render-runtime-override* ~runtime)
       ~@body
       (finally
         (reset! wind-gen-render-runtime-override* prev-override#)))))

(defn call-with-wind-gen-render-runtime
  [runtime f]
  (let [prev-override @wind-gen-render-runtime-override*]
    (try
      (reset! wind-gen-render-runtime-override* runtime)
      (f)
      (finally
        (reset! wind-gen-render-runtime-override* prev-override)))))

(defn- fan-rot-cache-atom
	[]
	(:fan-rot-cache (current-wind-gen-render-runtime)))

(defn fan-rot-cache-snapshot
	[]
	@(fan-rot-cache-atom))

(defn clear-fan-rot-cache!
	[]
	(reset! (fan-rot-cache-atom) {})
	nil)

(defn reset-fan-rot-cache-for-test!
	([]
	 (clear-fan-rot-cache!))
	([cache]
	 (reset! (fan-rot-cache-atom) cache)
	 nil))

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

(def ^:private wind-renderer-guard-lock
	(Object.))

(def ^:private ^:dynamic *wind-renderer-installed?*
	false)

(defn init!
	[]
	(when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
		(when-not (var-get #'*wind-renderer-installed?*)
			(locking wind-renderer-guard-lock
				(when-not (var-get #'*wind-renderer-installed?*)
					(register-fn register!)
					(alter-var-root #'*wind-renderer-installed?* (constantly true))
					(log/info "Registered wind generator renderers"))))))