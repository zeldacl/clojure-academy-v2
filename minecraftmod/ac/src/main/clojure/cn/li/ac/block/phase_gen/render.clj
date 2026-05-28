(ns cn.li.ac.block.phase-gen.render
  "CLIENT-ONLY: Phase generator block entity renderer.

  Ported from AcademyCraft's RenderPhaseGen:
  - Use ip_gen.obj
  - Texture frame depends on liquid amount ratio (0..4)."
  (:require [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.obj :as obj]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]))

(def ^:private tank-size 8000)

(def ^:private phase-render-resource-lock
  (Object.))

(def ^:private ^:dynamic *phase-model*
  nil)

(def ^:private ^:dynamic *phase-textures*
  nil)

(defn- phase-model
  []
  (or (var-get #'*phase-model*)
      (locking phase-render-resource-lock
        (or (var-get #'*phase-model*)
            (let [m (res/load-obj-model "ip_gen")]
              (alter-var-root #'*phase-model* (constantly m))
              m)))))

(defn- phase-textures
  []
  (or (var-get #'*phase-textures*)
      (locking phase-render-resource-lock
        (or (var-get #'*phase-textures*)
            (let [textures [(res/texture-location "models/ip_gen0")
                            (res/texture-location "models/ip_gen1")
                            (res/texture-location "models/ip_gen2")
                            (res/texture-location "models/ip_gen3")
                            (res/texture-location "models/ip_gen4")]]
              (alter-var-root #'*phase-textures* (constantly textures))
              textures)))))

(defn- clamp-frame
  [v]
  (-> v (max 0) (min 4)))

(defn- state->texture-index
  [state]
  (let [liquid (max 0 (long (get state :liquid-amount 0)))
        ratio (if (pos? tank-size) (/ (double liquid) (double tank-size)) 0.0)]
    (clamp-frame (int (Math/round (* 4.0 ratio))))))

(defn- render-at-origin!
  [tile pose-stack buffer-source packed-light packed-overlay]
  (let [state (or (platform-be/get-custom-state tile) {})
        tex-idx (state->texture-index state)
        textures (phase-textures)
        tex (nth textures tex-idx (first textures))
        vc (rb/get-solid-buffer buffer-source tex)]
    (pose/push-pose pose-stack)
    (try
      ;; Scripted BER origin is block corner; move OBJ to center like original.
      (pose/translate pose-stack 0.5 0.0 0.5)
      (binding [obj/*skip-flat-bottom-plane* true
                obj/*bottom-plane-epsilon* 0.0008]
        (obj/render-all! (phase-model) pose-stack vc packed-light packed-overlay))
      (finally
        (pose/pop-pose pose-stack)))))

(defn register!
  []
  (tesr-api/register-scripted-tile-renderer!
    "phase-gen"
    (reify tesr-api/ITileEntityRenderer
      (render-tile [_ tile-entity _partial-ticks pose-stack buffer-source packed-light packed-overlay]
        (try
          (render-at-origin! tile-entity pose-stack buffer-source packed-light packed-overlay)
          (catch Exception e
            (log/error "Error in phase generator renderer:" (ex-message e))
            (.printStackTrace e)))))))

(def ^:private phase-renderer-guard-lock
  (Object.))

(def ^:private ^:dynamic *phase-renderer-installed?*
  false)

(defn init!
  []
  (when-let [register-fn (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)]
    (when-not (var-get #'*phase-renderer-installed?*)
      (locking phase-renderer-guard-lock
        (when-not (var-get #'*phase-renderer-installed?*)
          (register-fn register!)
          (alter-var-root #'*phase-renderer-installed?* (constantly true))
          (log/info "Registered phase generator renderer"))))))
