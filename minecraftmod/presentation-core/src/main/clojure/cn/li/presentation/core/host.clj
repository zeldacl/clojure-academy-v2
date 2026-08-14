(ns cn.li.presentation.core.host
  "Runtime Host adapter. It depends on mcmod's neutral domain contract but is
   not part of minecraft/base; platform code consumes only the public API."
  (:require [cn.li.presentation.core.runtime :as runtime])
  (:import [cn.li.presentation.core HostDescriptor HostDescriptor$HostKind
            HostDescriptor$InputPolicy TemplateId PresentationRuntime
            MountHandle PresentationInputEvent FrameContext FramePacket RenderPass
            RenderBackend RenderStage]))

(defn create
  ([] (create {}))
  ([options]
   (let [state (runtime/create-runtime options)]
    {:state state
     :api (reify PresentationRuntime
            (mount [_ host template model]
              (cast MountHandle (runtime/mount! state host template model)))
            (^void transact [_ ^Runnable mutation]
              (runtime/transact! state #(.run mutation))
              nil)
            (^void dispatch [_ ^MountHandle mount ^PresentationInputEvent event]
              (runtime/dispatch! state mount event)
              nil)
            (^FramePacket extract [_ ^FrameContext context]
              (cast cn.li.presentation.core.FramePacket (runtime/extract! state context)))
            (^void unmount [_ ^MountHandle mount]
              (runtime/unmount! state mount)
              nil))})))

(defn mount-host! [runtime id kind template model]
  (let [kind (case kind
               :hud HostDescriptor$HostKind/HUD
               :world-ui HostDescriptor$HostKind/WORLD_UI
               :vfx HostDescriptor$HostKind/VFX
               :first-person HostDescriptor$HostKind/FIRST_PERSON
               :camera HostDescriptor$HostKind/CAMERA
               :post-process HostDescriptor$HostKind/POST_PROCESS
               :screen HostDescriptor$HostKind/SCREEN
               (throw (ex-info "unknown presentation host" {:kind kind})))
        host (HostDescriptor. (name id) kind 0 0 nil HostDescriptor$InputPolicy/PASSTHROUGH)]
    (runtime/mount! (or (:state runtime) runtime)
                    host (TemplateId. template) model)))

(defn mount-tree-host! [runtime id kind template model spec]
  (let [kind (case kind
               :hud HostDescriptor$HostKind/HUD
               :world-ui HostDescriptor$HostKind/WORLD_UI
               :vfx HostDescriptor$HostKind/VFX
               :first-person HostDescriptor$HostKind/FIRST_PERSON
               :camera HostDescriptor$HostKind/CAMERA
               :post-process HostDescriptor$HostKind/POST_PROCESS
               :screen HostDescriptor$HostKind/SCREEN
               (throw (ex-info "unknown presentation host" {:kind kind})))
        host (HostDescriptor. (name id) kind 0 0 nil HostDescriptor$InputPolicy/PASSTHROUGH)]
    (runtime/mount-tree! (or (:state runtime) runtime)
                          host (TemplateId. template) model spec)))

(defn reconcile-tree! [runtime mount spec]
  (runtime/reconcile-tree! (or (:state runtime) runtime) mount spec))

(defn layout-tree! [runtime mount width height]
  (runtime/layout-tree! (or (:state runtime) runtime) mount width height))

(defn set-input-handler! [runtime mount handler]
  (runtime/set-input-handler! (or (:state runtime) runtime) mount handler))

(defn dispatch-input!
  "Dispatch an input event through the Clojure runtime and retain its result.

   The public Java PresentationRuntime contract intentionally remains void;
   version boundaries use this Clojure bridge when they need PASS/CONSUME
   routing for native Minecraft input fallback."
  [runtime mount event]
  (runtime/dispatch! (or (:state runtime) runtime) mount event))

(defn frame! [runtime frame-id delta-seconds width height]
  (runtime/extract! (or (:state runtime) runtime)
                    (FrameContext. frame-id delta-seconds width height)))

(defn unmount-all! [runtime]
  (runtime/unmount-all! (or (:state runtime) runtime)))

(defn unmount! [runtime mount]
  (runtime/unmount! (or (:state runtime) runtime) mount))

(defn effect-runtime [runtime]
  (runtime/effect-runtime (or (:state runtime) runtime)))

(defn spawn-effect! [runtime template-id owner params now-ms]
  (runtime/spawn-effect! (or (:state runtime) runtime)
                         template-id owner params now-ms))

(defn destroy-effect! [runtime instance-id]
  (runtime/destroy-effect! (or (:state runtime) runtime) instance-id))

(defn clear-effect-owner! [runtime owner]
  (runtime/clear-effect-owner! (or (:state runtime) runtime) owner))

(defn tick-effects! [runtime delta-ms]
  (runtime/tick-effects! (or (:state runtime) runtime) delta-ms))

(defn reload-resources! [runtime generation]
  (runtime/reload-resources! (or (:state runtime) runtime) generation))

(defn submit-frame! [^RenderBackend backend ^FramePacket frame]
  (doseq [^RenderPass pass (.passes frame)]
    (.submit backend frame ^RenderStage (.stage pass)))
  frame)
