(ns my-mod.client.render.buffer
  "Platform-neutral render buffer API for model rendering.

  Platform adapters register two functions during platform init:
  - solid buffer selection
  - translucent buffer selection

  ac/mcmod rendering code should call this namespace instead of referencing
  platform RenderType classes directly.")

(def ^:dynamic *solid-buffer-fn* nil)
(def ^:dynamic *translucent-buffer-fn* nil)
(def ^:dynamic *cutout-no-cull-buffer-fn* nil)

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