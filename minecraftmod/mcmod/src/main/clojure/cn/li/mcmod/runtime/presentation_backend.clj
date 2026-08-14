(ns cn.li.mcmod.runtime.presentation-backend
  "Neutral backend protocol data. Platform implementations consume these maps
   and translate them to mapped GPU APIs without depending on presentation-core.")

(def profiles
  {:mc-1-20-1 {:instancing? false :streaming-buffers? false :ubo? false :post-process? true}
   :mc-1-21-1 {:instancing? false :streaming-buffers? false :ubo? false :post-process? true}
   :mc-26-2 {:instancing? true :streaming-buffers? true :ubo? true :post-process? true}})

(def command-kinds
  "Complete neutral Render IR vocabulary. Version backends must dispatch every
   kind; resource-specific work is delegated through explicit render-context
   callbacks rather than being silently dropped."
  #{"quad" "image" "glyph-run" "push-clip" "pop-clip" "layer"
    "mesh" "billboard" "particle-batch" "ribbon" "beam" "item-preview"
    "camera-contribution" "post-process" "order-barrier"})

(defn command-kind-known? [kind]
  (contains? command-kinds kind))

(declare submit! reload-resources!)

(defn create [profile]
  (let [backend (atom nil)]
    (let [value {:profile profile
                 :capabilities (get profiles profile {})
                 :submissions (atom [])
                 :resource-generation (atom 0)
                 :renderer (atom nil)}]
      ;; Keep the callback opaque to loader/base.  The version backend owns
      ;; the mapping from this envelope to GuiGraphics/BufferBuilder/etc.;
      ;; this neutral object only records the hand-off for diagnostics and
      ;; conformance tests.
      (reset! backend (assoc value :submit!
                             (fn [stage frame-packet & [render-context]]
                               (let [value @backend]
                                 (submit! value stage frame-packet)
                                 (when-let [render! @(:renderer value)]
                                   (render! render-context stage frame-packet))
                                 value))
                             :reload!
                             (fn [generation]
                               (reload-resources! @backend generation))))
      @backend)))

(defn submit! [backend stage frame-packet]
  (swap! (:submissions backend) conj {:stage stage :frame-packet frame-packet})
  backend)

(defn reload-resources! [backend generation]
  (reset! (:resource-generation backend) generation)
  backend)

(defn install-renderer! [backend renderer]
  (when-not (fn? renderer)
    (throw (ex-info "presentation renderer must be a function" {})))
  (reset! (:renderer backend) renderer)
  backend)
