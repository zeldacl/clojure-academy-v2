(ns cn.li.mcmod.runtime.presentation-backend
  "Neutral backend protocol data. Platform implementations consume these maps
   and translate them to mapped GPU APIs without depending on presentation-core."
  (:import [cn.li.mcmod.runtime RenderStage]))

(def profiles
  {:mc-1-20-1 {:instancing? false :streaming-buffers? false :ubo? false :post-process? true}
   :mc-1-21-1 {:instancing? false :streaming-buffers? false :ubo? false :post-process? true}
   :mc-26-2 {:instancing? true :streaming-buffers? true :ubo? true :post-process? true}})

(def ^:private stage-keywords
  "Single keyword<->RenderStage mapping for the loader-facing neutral seam.
   Replaces three hand-duplicated `stage-name` case forms in the version
   backends and the parallel keyword list in mcbase's host-lifecycle."
  {:world-before-translucent RenderStage/WORLD_BEFORE_TRANSLUCENT
   :world-after-translucent  RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person             RenderStage/FIRST_PERSON
   :hud-underlay             RenderStage/HUD_UNDERLAY
   :hud                      RenderStage/HUD
   :hud-overlay              RenderStage/HUD_OVERLAY
   :screen                   RenderStage/SCREEN
   :post-process             RenderStage/POST_PROCESS})

(defn stage->render-stage ^RenderStage [stage]
  (or (get stage-keywords stage)
      (throw (ex-info "unknown presentation render stage" {:stage stage}))))

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
