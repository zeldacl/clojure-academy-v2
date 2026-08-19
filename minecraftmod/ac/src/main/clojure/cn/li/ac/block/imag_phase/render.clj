(ns cn.li.ac.block.imag-phase.render
  "CLIENT-ONLY: Imag Phase liquid overlay TESR.

  Renders 3 scrolling overlay quad layers above the fluid surface,
  mirroring the original AcademyCraft RenderImagPhaseLiquid behavior.

  Uses the project's modern render pipeline: PoseStack + VertexConsumer
  instead of legacy fixed-function GL. Follows the cat_engine/render.clj
  pattern for quad submission and lazy resource loading.

  Minecraft-specific queries (fluid height) go through platform-be bridge."
  (:require [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
            [cn.li.mcmod.client.platform-bridge :as bridge]
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
;; Quad geometry — horizontal quad on XZ plane
;; ---------------------------------------------------------------------------

;; [x y z u v] — y=0 (translated to computed height via pose/translate)
(def ^:private quad-vertices
  [[0.0 0.0 0.0 0.0 0.0]   ;; (x=0, y=0, z=0)
   [1.0 0.0 0.0 1.0 0.0]   ;; (x=1, y=0, z=0)
   [1.0 0.0 1.0 1.0 1.0]   ;; (x=1, y=0, z=1)
   [0.0 0.0 1.0 0.0 1.0]]) ;; (x=0, y=0, z=1)

;; The translucent RenderType draws in QUADS mode: exactly 4 vertices per quad,
;; in ring order. Emitting 6 (two triangles) makes the buffer index only the
;; first 4 and stitch the leftovers onto the next block entity's vertices.
(def ^:private quad-vertex-order [0 1 2 3])

;; ---------------------------------------------------------------------------
;; Vertex submission
;; ---------------------------------------------------------------------------

(defn- submit-scrolling-quad!
  "Submit a single scrolling quad layer.

  `see-through?` selects the buffer's vertex format: the see-through translucent
  type is POSITION_COLOR_TEX_LIGHTMAP (no overlay, no normal), the fallback
  entity type also wants an overlay and an upward normal."
  [vc pose-stack du dv density packed-light packed-overlay alpha see-through?]
  (doseq [idx quad-vertex-order
          :let [[x y z u v] (nth quad-vertices idx)
                tex-u (+ (double du) (* (double u) (double density)))
                tex-v (+ (double dv) (* (double v) (double density)))]]
    (if see-through?
      (rb/submit-vertex-no-overlay vc pose-stack
                                   (double x) (double y) (double z)
                                   1.0 1.0 1.0 (float alpha)
                                   tex-u tex-v
                                   (int packed-light))
      (rb/submit-vertex vc pose-stack
                        (double x) (double y) (double z)
                        1.0 1.0 1.0 (float alpha)
                        tex-u tex-v
                        (int packed-overlay)
                        (int packed-light)
                        0.0 1.0 0.0))))

;; ---------------------------------------------------------------------------
;; Distance fade — upstream alpha = 1/(1 + 0.2*dist), block skipped below 0.1
;; ---------------------------------------------------------------------------

(def ^:private min-visible-alpha 0.1)

;; The TESR runs once per pool block per frame and `call-adapter` costs a
;; Framework deref plus a map lookup, so the viewer position is read at most
;; once per frame-length window and shared by every block in the pool. Held in
;; the shared render cache (same governance as cat-engine's rotor cache) rather
;; than a namespace-local atom.
(def ^:private viewer-pos-refresh-ms 16)
(def ^:private viewer-pos-cache-key :imag-phase-viewer-pos)
(def ^:private viewer-pos-initial {:at-ms 0 :pos nil})

(defn clear-viewer-pos-cache!
  "Drop the cached viewer position; the next render re-reads it."
  []
  (machine-render-runtime/clear-render-cache! viewer-pos-cache-key viewer-pos-initial))

(defn- viewer-pos []
  (let [now (System/currentTimeMillis)
        {:keys [at-ms pos]} (machine-render-runtime/render-cache
                              viewer-pos-cache-key viewer-pos-initial)]
    (if (< (- now at-ms) viewer-pos-refresh-ms)
      pos
      (let [fresh (bridge/call-adapter :local-player-pos)]
        (machine-render-runtime/put-render-cache!
          viewer-pos-cache-key {:at-ms now :pos fresh})
        fresh))))

(defn- distance-alpha
  "Upstream RenderImagPhaseLiquid fades the overlay out with viewer distance:
  alpha = 1/(1 + 0.2*dist) from the block centre, and skips the block entirely
  below 0.1 (~45 blocks). Returns 1.0 when the loader installs no
  :local-player-pos op (fabric), i.e. the un-faded look stays the fallback."
  [tile]
  (if-let [viewer (viewer-pos)]
    (let [p (pos/block-pos tile)
          dx (- (+ 0.5 (double (pos/pos-x p))) (double (:x viewer)))
          dy (- (+ 0.5 (double (pos/pos-y p))) (double (:y viewer)))
          dz (- (+ 0.5 (double (pos/pos-z p))) (double (:z viewer)))
          dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
      (/ 1.0 (+ 1.0 (* 0.2 dist))))
    1.0))

;; ---------------------------------------------------------------------------
;; Main render
;; ---------------------------------------------------------------------------

(defn- render-layer!
  "Render one scrolling overlay layer."
  [pose-stack buffer-source packed-light packed-overlay alpha see-through?
   {:keys [idx height-fn vx vz density]} ht time]
  (let [height (height-fn ht)
        du (mod (* time vx) 1.0)
        dv (mod (* time vz) 1.0)
        textures (:layer-textures (imag-phase-resources))
        tex (nth textures idx)
        vc (if see-through?
             (rb/get-translucent-see-through-buffer buffer-source tex)
             (rb/get-translucent-buffer buffer-source tex))]
    (pose/push-pose pose-stack)
    (try
      (pose/translate pose-stack 0.0 height 0.0)
      (submit-scrolling-quad! vc pose-stack du dv density
                              packed-light packed-overlay alpha see-through?)
      (finally
        (pose/pop-pose pose-stack)))))

(defn- render-imag-phase!
  "Render the full Imag Phase liquid overlay effect.
  3 scrolling layers at different heights, fullbright illumination."
  [tile pose-stack buffer-source _packed-light packed-overlay]
  (let [alpha (distance-alpha tile)]
    (when (>= alpha min-visible-alpha)
      (let [fluid-height (platform-be/get-fluid-height tile)
            ht (* 1.2 (Math/sqrt (max 0.0 fluid-height)))
            time (render/get-render-time)
            ;; Upstream drew with depth test and depth write off, so the layers
            ;; composite with each other from any angle, keep the sheet that
            ;; sits below the block, and never punch holes in the fluid surface
            ;; drawn after them. Every current loader installs this; the
            ;; fallback is the depth-tested entity translucent buffer.
            see-through? (rb/translucent-see-through-available?)]
        (doseq [layer layer-defs]
          (when (or (nil? (:condition layer))
                    ((:condition layer) ht))
            ;; packed-overlay must come from the renderer: a hardcoded 0 packs
            ;; to overlay v=0, which lands in the red "hurt" rows and tints the
            ;; layer.
            (render-layer! pose-stack buffer-source
                           fullbright-packed-light packed-overlay alpha
                           see-through? layer ht time)))))))

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
                         (log/debug "Error in imag-phase renderer:" (ex-message e)))))}))

(defn init!
  "Client-side renderer init hook. Called by the AC hook registry during
  client setup."
  []
  (machine-render-runtime/register-client-renderer-init!
    'cn.li.ac.block.imag-phase.render/register!))
