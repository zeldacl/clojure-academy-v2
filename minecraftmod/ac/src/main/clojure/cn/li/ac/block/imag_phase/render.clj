(ns cn.li.ac.block.imag-phase.render
  "CLIENT-ONLY: Imag Phase liquid overlay TESR.

  Renders 3 scrolling overlay quad layers above the fluid surface,
  mirroring the original AcademyCraft RenderImagPhaseLiquid behavior.

  Uses the project's modern render pipeline: PoseStack + VertexConsumer
  instead of legacy fixed-function GL. Follows the cat_engine/render.clj
  pattern for quad submission and lazy resource loading.

  Minecraft-specific queries (fluid height) go through platform-be bridge."
  (:require [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
            [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]))

;; ---------------------------------------------------------------------------
;; Lazy resource loading (same pattern as cat-engine/render.clj)
;; ---------------------------------------------------------------------------

(def ^:private imag-phase-resources-holder nil)
(def ^:private imag-phase-resources
  (machine-render-runtime/lazy-resources #'imag-phase-resources-holder
    {:layer-textures #(vec [(res/texture-location "effects/imag_proj_liquid/0")
                             (res/texture-location "effects/imag_proj_liquid/1")
                             (res/texture-location "effects/imag_proj_liquid/2")])}))

;; ---------------------------------------------------------------------------
;; Layer definitions — mirror the original 3-layer configuration
;; ---------------------------------------------------------------------------

(def ^:private layer-defs
  [{:idx 0 :height-fn #(* -0.3 %) :vx 0.3 :vz 0.2 :density 0.7}       ;; always
   {:idx 1 :height-fn #(* 0.35 %) :vx 0.3 :vz 0.05 :density 0.7}      ;; always
   {:idx 2 :height-fn #(* 0.7 %)  :vx 0.1 :vz 0.25 :density 0.7       ;; only when ht > 0.5
    :condition (fn [ht] (> ht 0.5))}])

;; Fullbright packed-light constant: LightTexture/pack(15, 15) = 15728880
(def ^:private fullbright-packed-light 15728880)

;; ---------------------------------------------------------------------------
;; Fluid height — uses platform bridge (no reflection, no Minecraft class deps)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Quad geometry — horizontal quad on XZ plane, 6 vertices = 2 triangles
;; Pattern matches cat_engine/render.clj (TRIANGLES draw mode)
;; ---------------------------------------------------------------------------

;; [x y z u v] — y=0 (translated to computed height via pose/translate)
(def ^:private quad-vertices
  [[0.0 0.0 0.0 0.0 0.0]   ;; (x=0, y=0, z=0)
   [1.0 0.0 0.0 1.0 0.0]   ;; (x=1, y=0, z=0)
   [1.0 0.0 1.0 1.0 1.0]   ;; (x=1, y=0, z=1)
   [0.0 0.0 1.0 0.0 1.0]]) ;; (x=0, y=0, z=1)

(def ^:private quad-indices [0 1 2 0 2 3])

;; ---------------------------------------------------------------------------
;; Vertex submission
;; ---------------------------------------------------------------------------

(defn- submit-scrolling-quad!
  "Submit a single scrolling quad layer. Normal points upward (0, 1, 0)."
  [vc pose-stack du dv density packed-light packed-overlay alpha]
  (doseq [idx quad-indices
          :let [[x y z u v] (nth quad-vertices idx)]]
    (rb/submit-vertex vc pose-stack
                      (double x) (double y) (double z)
                      1.0 1.0 1.0 (float alpha)
                      (+ (double du) (* (double u) (double density)))
                      (+ (double dv) (* (double v) (double density)))
                      (int packed-overlay)
                      (int packed-light)
                      0.0 1.0 0.0)))

;; NOTE: Distance-based alpha fade omitted for now. The original used
;; alpha = 1/(1 + 0.2*sqrt(distSq)) with cull at alpha < 0.1.
;; Re-adding this requires a platform bridge for camera position.
;; Current render uses full alpha (1.0) — always visible overlay.

;; ---------------------------------------------------------------------------
;; Main render
;; ---------------------------------------------------------------------------

(defn- render-layer!
  "Render one scrolling overlay layer."
  [pose-stack buffer-source packed-light packed-overlay
   {:keys [idx height-fn vx vz density]} ht time]
  (let [height (height-fn ht)
        du (mod (* time vx) 1.0)
        dv (mod (* time vz) 1.0)
        textures (:layer-textures (imag-phase-resources))
        tex (nth textures idx)
        vc (rb/get-translucent-buffer buffer-source tex)]
    (pose/push-pose pose-stack)
    (try
      (pose/translate pose-stack 0.0 height 0.0)
      (submit-scrolling-quad! vc pose-stack du dv density
                              packed-light packed-overlay 1.0)
      (finally
        (pose/pop-pose pose-stack)))))

(defn- render-imag-phase!
  "Render the full Imag Phase liquid overlay effect.
  3 scrolling layers at different heights, fullbright illumination."
  [tile pose-stack buffer-source _packed-light _packed-overlay]
  (let [fluid-height (platform-be/get-fluid-height tile)
        ht (* 1.2 (Math/sqrt (max 0.0 fluid-height)))
        time (render/get-render-time)]
    (doseq [def layer-defs]
      (when (or (nil? (:condition def))
                ((:condition def) ht))
        (render-layer! pose-stack buffer-source
                       fullbright-packed-light 0
                       def ht time)))))

;; ---------------------------------------------------------------------------
;; Registration (cat_engine/render.clj pattern)
;; ---------------------------------------------------------------------------

(defn register!
  "Register the imag-phase TESR via the scripted tile renderer registry."
  []
  (tesr-api/register-scripted-tile-renderer!
    "imag-phase"
    {:render-tile (fn [tile-entity _partial-ticks pose-stack buffer-source packed-light packed-overlay]
                     (try
                       (render-imag-phase! tile-entity pose-stack buffer-source
                                           packed-light packed-overlay)
                       (catch Exception e
                         (log/error "Error in imag-phase renderer:" (ex-message e)))))}))

(defn init!
  "Client-side renderer init hook. Called by the AC hook registry during
  client setup."
  []
  (machine-render-runtime/register-client-renderer-init!
    'cn.li.ac.block.imag-phase.render/register!))
