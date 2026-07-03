(ns cn.li.mcmod.client.render.buffer
  "Render buffer API for model rendering via Framework function map.

   Buffer ops stored at [:platform :render-buffer-ops]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn- buffer-op [k]
  (get-in @(fw/fw-atom) [:platform :render-buffer-ops k]))

(defn install-render-buffer-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :render-buffer-ops] ops-map)) nil)

(defn render-buffer-ops-available? []
  (boolean (get-in @(fw/fw-atom) [:platform :render-buffer-ops])))

(defn call-with-render-buffer-ops [ops f] (f ops))

(defn- require-buffer-fn [buffer-fn kind]
  (or buffer-fn
      (throw (ex-info (str "Render buffer function not initialized: " kind)
                      {:kind kind :hint "Call install-render-buffer-ops! before renderer registration"}))))

(defn get-solid-buffer [buffer-source texture]
  ((require-buffer-fn (buffer-op :solid) :solid) buffer-source texture))

(defn get-translucent-buffer [buffer-source texture]
  ((require-buffer-fn (buffer-op :translucent) :translucent) buffer-source texture))

(defn get-cutout-no-cull-buffer [buffer-source texture]
  ((require-buffer-fn (buffer-op :cutout-no-cull) :cutout-no-cull) buffer-source texture))

(defn triangle-vertex-order []
  (or (buffer-op :triangle-vertex-order) [0 1 2]))

(defn submit-vertex [vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz]
  (let [submit-fn (or (buffer-op :submit-vertex)
                      (throw (ex-info "No platform submit-vertex function bound"
                                      {:hint "Call install-render-buffer-ops! during client init"})))]
    (submit-fn vertex-consumer pose-stack x y z r g b a u v overlay uv2 nx ny nz)))
