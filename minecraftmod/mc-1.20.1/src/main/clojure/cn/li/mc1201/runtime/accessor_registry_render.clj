(ns cn.li.mc1201.runtime.accessor-registry-render
  "Render-domain accessor registrations.
   
   Provides platform-independent accessors for rendering operations:
   - Model rendering
   - Texture mapping
   - Animation control
   - Lighting"
  (:require [cn.li.mc1201.runtime.accessor-registry-core :as core]))

;; ============================================================================
;; Render Accessor Registrations
;; ============================================================================

(defn- register-render-accessors-impl!
  "Register all render-domain accessors.
   
   This function should be called during mod initialization to populate
   the render accessor registry with all available rendering operations."
  []
  ;; Model rendering
  (core/register-accessor! :render :queue-model-render
    (fn [render-context model-id pos rotation scale] nil)
    "Queue a model for rendering. Parameters: model-id (keyword), pos (map), rotation (map), scale (float)")
  
  (core/register-accessor! :render :queue-block-render
    (fn [render-context block-id pos rotation] nil)
    "Queue block model rendering. Parameters: block-id (keyword), pos (map), rotation (map)")
  
  (core/register-accessor! :render :get-model
    (fn [model-id] nil)
    "Load model by identifier. Returns model data structure.")
  
  ;; Texture operations
  (core/register-accessor! :render :bind-texture
    (fn [texture-id] nil)
    "Bind texture for rendering. Parameters: texture-id (keyword or path)")
  
  (core/register-accessor! :render :get-texture-uv
    (fn [texture-id] nil)
    "Get UV coordinates for texture. Returns {:u0 :v0 :u1 :v1}")
  
  (core/register-accessor! :render :register-texture
    (fn [texture-id texture-path] nil)
    "Register texture resource. Parameters: texture-id (keyword), texture-path (string)")
  
  ;; Animation control
  (core/register-accessor! :render :play-animation
    (fn [render-context animation-id loop? speed] nil)
    "Play animation. Parameters: animation-id (keyword), loop? (bool), speed (float)")
  
  (core/register-accessor! :render :stop-animation
    (fn [render-context animation-id] nil)
    "Stop playing animation. Parameters: animation-id (keyword)")
  
  (core/register-accessor! :render :get-animation-frame
    (fn [render-context animation-id] nil)
    "Get current animation frame. Returns frame index (int)")
  
  (core/register-accessor! :render :set-animation-speed
    (fn [render-context animation-id speed] nil)
    "Set animation playback speed. Parameters: speed (float, 1.0 = normal)")
  
  ;; Lighting
  (core/register-accessor! :render :set-block-light
    (fn [pos light-level] nil)
    "Set block light level at position. Parameters: light-level (0-15)")
  
  (core/register-accessor! :render :set-ambient-light
    (fn [level] nil)
    "Set ambient light level. Parameters: level (float, 0-1)")
  
  (core/register-accessor! :render :set-light-color
    (fn [color-vector] nil)
    "Set light color. Parameters: color-vector (RGBA float array)")
  
  ;; Shader operations
  (core/register-accessor! :render :set-shader-uniform
    (fn [shader-id uniform-name value] nil)
    "Set shader uniform value. Parameters: shader-id (keyword), uniform-name (string)")
  
  (core/register-accessor! :render :bind-shader
    (fn [shader-id] nil)
    "Bind shader program. Parameters: shader-id (keyword)")
  
  ;; Camera/view
  (core/register-accessor! :render :get-camera-pos
    (fn [] nil)
    "Get current camera position. Returns {:x :y :z}")
  
  (core/register-accessor! :render :get-camera-rotation
    (fn [] nil)
    "Get current camera rotation. Returns {:yaw :pitch}")
  
  nil)

(def ^:private render-accessor-guard-lock
  (Object.))

(def ^:private ^:dynamic *render-accessors-registered?*
  false)

(defn register-render-accessors!
  "Explicitly register render-domain accessors once."
  []
  (when-not (var-get #'*render-accessors-registered?*)
    (locking render-accessor-guard-lock
      (when-not (var-get #'*render-accessors-registered?*)
        (try
          (register-render-accessors-impl!)
          (alter-var-root #'*render-accessors-registered?* (constantly true))
          (catch Throwable t
            (alter-var-root #'*render-accessors-registered?* (constantly false))
            (throw (ex-info "Failed to register render accessors" {} t)))))))
  nil)
