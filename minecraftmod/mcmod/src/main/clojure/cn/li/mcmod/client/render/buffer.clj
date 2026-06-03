(ns cn.li.mcmod.client.render.buffer
  "Platform-neutral render buffer API for model rendering.

  Platform adapters call `install-render-buffer-ops!` once at client bootstrap."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *render-buffer-ops* nil)

(defn- buffer-op [k]
  (when *render-buffer-ops* (get *render-buffer-ops* k)))

(defn install-render-buffer-ops!
  "Install render buffer ops. Keys:
  :solid :translucent :cutout-no-cull — (fn [buffer-source texture] consumer)
  :submit-vertex — vertex submit fn
  :triangle-vertex-order — vector, default [0 1 2]"
  [ops-map label]
  (prt/install-impl! #'*render-buffer-ops* ops-map (or label "render-buffer-ops"))
  nil)

(defn render-buffer-ops-available? []
  (prt/impl-available? #'*render-buffer-ops*))

(defn call-with-render-buffer-ops [ops f]
  (binding [*render-buffer-ops* ops] (f)))

(defn- require-buffer-fn
  [buffer-fn kind]
  (or buffer-fn
      (throw (ex-info (str "Render buffer function not initialized: " kind)
                      {:kind kind
                       :hint "Call install-render-buffer-ops! before renderer registration"}))))

(defn get-solid-buffer
  [buffer-source texture]
  ((require-buffer-fn (buffer-op :solid) :solid) buffer-source texture))

(defn get-translucent-buffer
  [buffer-source texture]
  ((require-buffer-fn (buffer-op :translucent) :translucent) buffer-source texture))

(defn get-cutout-no-cull-buffer
  [buffer-source texture]
  ((require-buffer-fn (buffer-op :cutout-no-cull) :cutout-no-cull) buffer-source texture))

(defn triangle-vertex-order
  []
  (or (buffer-op :triangle-vertex-order) [0 1 2]))

(defn submit-vertex
  [vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz]
  (let [submit-fn (or (buffer-op :submit-vertex)
                      (throw (ex-info "No platform submit-vertex function bound"
                                      {:hint "Call install-render-buffer-ops! during client init"})))]
    (submit-fn vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz)))
