(ns cn.li.presentation.core.host
  "Runtime Host adapter. It depends on mcmod's neutral domain contract but is
   not part of minecraft/base; platform code consumes only the public API."
  (:require [cn.li.presentation.core.runtime :as runtime])
  (:import [cn.li.presentation.core HostDescriptor HostDescriptor$HostKind
            HostDescriptor$InputPolicy TemplateId PresentationRuntime
            MountHandle PresentationInputEvent FrameContext
            RenderBackend]
           [cn.li.mcmod.runtime FramePacket RenderPass RenderStage]))

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
              (cast cn.li.mcmod.runtime.FramePacket (runtime/extract! state context)))
            (^void unmount [_ ^MountHandle mount]
              (runtime/unmount! state mount)
              nil))})))

(defn mount-host! [runtime id kind template model]
  (let [kind (case kind
               :hud HostDescriptor$HostKind/HUD
               :world-ui HostDescriptor$HostKind/WORLD_UI
               :screen HostDescriptor$HostKind/SCREEN
               (throw (ex-info "unknown presentation host" {:kind kind})))
        host (HostDescriptor. (name id) kind 0 0 nil HostDescriptor$InputPolicy/PASSTHROUGH)]
    (runtime/mount! (or (:state runtime) runtime)
                    host (TemplateId. template) model)))

(defn mount-tree-host! [runtime id kind template model spec]
  (let [kind (case kind
               :hud HostDescriptor$HostKind/HUD
               :world-ui HostDescriptor$HostKind/WORLD_UI
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

(defn submit-frame! [^RenderBackend backend ^FramePacket frame]
  (doseq [^RenderPass pass (.passes frame)]
    (.submit backend frame ^RenderStage (.stage pass)))
  frame)
