(ns cn.li.mcmod.client.render.buffer
  "Platform-neutral render buffer API for model rendering.

  Platform adapters register two functions during platform init:
  - solid buffer selection
  - translucent buffer selection

  ac/mcmod rendering code should call this namespace instead of referencing
  platform RenderType classes directly.")

(def ^:dynamic *solid-buffer-fn* nil)
(def ^:dynamic *translucent-buffer-fn* nil)
(def ^:dynamic *cutout-no-cull-buffer-fn* nil)

(def ^:dynamic *submit-vertex-fn* nil)

(def ^:dynamic *triangle-vertex-order*
  "Backend triangle emission order.

  Default `[0 1 2]` emits one triangle (OBJ-spec semantics).
  Some backend buffers are quad-oriented and may set `[0 1 2 2]` so each
  triangle is submitted as a degenerate quad without changing core OBJ logic."
  [0 1 2])

(defn- require-buffer-fn
  [buffer-fn kind]
  (or buffer-fn
      (throw (ex-info (str "Render buffer function not initialized: " kind)
                      {:kind kind
                       :hint "Call platform-impl/init-platform! before renderer registration"}))))

(defn get-solid-buffer
  "Return a solid VertexConsumer for `texture` from `buffer-source`.

  Throws ex-info when no platform implementation is registered."
  [buffer-source texture]
  ((require-buffer-fn *solid-buffer-fn* :solid) buffer-source texture))

(defn get-translucent-buffer
  "Return a translucent VertexConsumer for `texture` from `buffer-source`.

  Throws ex-info when no platform implementation is registered."
  [buffer-source texture]
  ((require-buffer-fn *translucent-buffer-fn* :translucent) buffer-source texture))

(defn get-cutout-no-cull-buffer
  "Return a cutout/no-cull VertexConsumer for `texture` from `buffer-source`.

  Useful for OBJ models with thin faces or uncertain winding where backface
  culling can make the model appear invisible."
  [buffer-source texture]
  ((require-buffer-fn *cutout-no-cull-buffer-fn* :cutout-no-cull) buffer-source texture))

(defn submit-vertex
  "Submit a single vertex to a platform `VertexConsumer`.

  Args:
  - vertex-consumer: platform vertex consumer
  - pose-stack: platform pose stack (for correct normals under scale)
  - x y z: vertex position (numbers)
  - r g b a: color components (ints 0-255)
  - u v: texture coords (numbers)
  - overlay: overlay coords (int)
  - uv2: light/uv2 packed int
  - nx ny nz: model-space normal (numbers)

  This delegates to a platform-provided function bound to `*submit-vertex-fn*`.
  "
  [vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz]
  (or *submit-vertex-fn*
      (throw (ex-info "No platform submit-vertex function bound"
                      {:hint "Call platform-impl/init-platform! to bind buffer helpers"})))
  (try
    (*submit-vertex-fn* vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz)
    (catch Exception e
      (throw e))))