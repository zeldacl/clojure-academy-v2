(ns cn.li.presentation.core.runtime
  (:require [cn.li.presentation.core.transaction :as tx]
            [cn.li.presentation.core.frame :as frame]
            [cn.li.presentation.core.dirty :as dirty]
            [cn.li.presentation.core.effects :as effects]
            [cn.li.presentation.core.tree :as tree]
            [cn.li.presentation.core.layout :as layout]
            [cn.li.presentation.core.input :as input])
  (:import [cn.li.presentation.core HostDescriptor MountHandle FrameContext FramePacket RenderPass RenderStage
            PresentationInputEvent PresentationInputEvent$Pointer PresentationInputEvent$Pointer$Type
            PresentationInputEvent$Key PresentationInputEvent$CharacterInput PresentationInputEvent$Scroll
            EventResult]
           [java.lang AutoCloseable]))

(defn create-runtime
  ([] (create-runtime {}))
  ([options]
   (let [runtime (atom {:mounts {} :next-id 1 :invalidated? true
                        :effects (effects/create)
                        :input (input/create)
                        :template-resolver (:template-resolver options)
                        :template-renderer (:template-renderer options)})
        dirty-state (dirty/create)]
      (swap! runtime assoc :dirty dirty-state)
      (assoc @runtime
             :state runtime
             :tx (tx/scheduler #(do (dirty/mark! dirty-state [:structure :paint])
                                    (swap! runtime assoc :invalidated? true)))))))

(defn- state [runtime] (:state runtime))

(defn- render-mount [mount context snapshot]
  (when-let [renderer (:template-renderer snapshot)]
    (let [template (if-let [resolver (:template-resolver snapshot)]
                     (resolver (:template mount))
                     (:template mount))]
      (when template
        (renderer template (:model mount)
                  {:width (.width ^FrameContext context)
                   :height (.height ^FrameContext context)
                   :host (:host mount)})))))

(defn- host-stage [^HostDescriptor host]
  (case (str (some-> host .kind))
    "HUD" :hud
    "WORLD_UI" :world-after-translucent
    "VFX" :world-after-translucent
    "FIRST_PERSON" :first-person
    "CAMERA" :world-after-translucent
    "POST_PROCESS" :post-process
    "SCREEN" :screen
    :hud))
(defn mount! [runtime host template model]
  (let [id (:next-id (swap! (state runtime) update :next-id inc))
        handle (MountHandle. (dec id))]
    (swap! (state runtime) assoc-in [:mounts handle]
           {:host host :template template :model model :commands (atom {})
            :handler nil :tree nil :layout nil
            :input-node (str "mount/" (.value handle))})
    (input/register-node! (:input @(state runtime))
                          (str "mount/" (.value handle)) nil
                          (fn [_phase event]
                            (if-let [handler (get-in @(state runtime) [:mounts handle :handler])]
                              (handler event)
                              EventResult/PASS)))
    (swap! (state runtime) assoc :invalidated? true)
    handle))

(defn mount-tree!
  "Mount a retained Clojure component tree alongside a template/model host."
  [runtime host template model spec]
  (let [handle (mount! runtime host template model)]
    (swap! (state runtime) assoc-in [:mounts handle :tree] (tree/node spec))
    (dirty/mark! (:dirty @(state runtime)) [:structure :measure :layout :paint])
    handle))

(defn retained-tree [runtime handle]
  (get-in @(state runtime) [:mounts handle :tree]))

(defn reconcile-tree! [runtime handle spec]
  (let [mount (get-in @(state runtime) [:mounts handle])]
    (when-not mount (throw (ex-info "unknown mount" {:mount handle})))
    (let [old (:tree mount)
          result (tree/reconcile old spec)]
      (swap! (state runtime) assoc-in [:mounts handle :tree] (:node result))
      (dirty/mark! (:dirty @(state runtime))
                   (if (:created? result)
                     [:structure :measure :layout :paint]
                     [:structure :measure :layout :paint]))
      (:node result))))

(defn layout-tree! [runtime handle width height]
  (let [mount (get-in @(state runtime) [:mounts handle])]
    (when-not mount (throw (ex-info "unknown mount" {:mount handle})))
    (let [root (some-> (:tree mount) (layout/layout width height))]
      (swap! (state runtime) assoc-in [:mounts handle :tree] root)
      (swap! (state runtime) assoc-in [:mounts handle :layout]
             (some-> root :layout))
      (dirty/mark! (:dirty @(state runtime)) [:layout :paint])
      root)))

(defn transact! [runtime mutation!] (tx/transact! (:tx runtime) mutation!))

(defn dirty-flags [runtime]
  @(-> runtime state :dirty))

(defn take-dirty! [runtime]
  (dirty/take! (-> runtime state :dirty)))

(defn emit! [runtime handle stage command]
  (let [mount (get-in @(state runtime) [:mounts handle])]
    (when-not mount (throw (ex-info "unknown mount" {:mount handle})))
    (swap! (:commands mount) update stage (fnil conj []) command)
    (dirty/mark! (:dirty @(state runtime)) [:paint])
    (swap! (state runtime) assoc :invalidated? true)))

(defn set-input-handler! [runtime handle handler]
  (swap! (state runtime) assoc-in [:mounts handle :handler] handler)
  nil)

(defn- normalize-input-event
  "Convert the neutral map emitted by Minecraft boundaries to the immutable
   Presentation Core event records before invoking a ViewModel handler."
  [event]
  (if-not (map? event)
    event
    (case (:type event)
      :pointer (PresentationInputEvent$Pointer.
                (cond
                  (#{:move :moved} (:event-type event)) PresentationInputEvent$Pointer$Type/MOVE
                  (#{:up :released} (:event-type event)) PresentationInputEvent$Pointer$Type/UP
                  :else PresentationInputEvent$Pointer$Type/DOWN)
                (float (or (:x event) 0.0))
                (float (or (:y event) 0.0))
                (int (or (:button event) -1)))
      :key (PresentationInputEvent$Key.
            (int (or (:key-code event) (:key event) 0))
            (boolean (if (contains? event :pressed?) (:pressed? event) true))
            (boolean (:shift? event)) (boolean (:control? event))
            (boolean (:alt? event)))
      :character (PresentationInputEvent$CharacterInput.
                  (str (or (:text event) "")) (boolean (:composing? event)))
      :scroll (PresentationInputEvent$Scroll.
               (float (or (:x event) 0.0))
               (float (or (:delta event) (:y event) 0.0)))
      event)))

(defn dispatch! [runtime handle event]
  (when-let [mount (get-in @(state runtime) [:mounts handle])]
    (let [result* (atom EventResult/PASS)
          node-id (:input-node mount)
          input-runtime (:input @(state runtime))
          event (normalize-input-event event)]
      (input/register-node! input-runtime node-id nil
                            (fn [phase e]
                              (let [result (if (= phase :target)
                                             (if-let [handler (:handler mount)]
                                               (handler e)
                                               EventResult/PASS)
                                             EventResult/PASS)]
                                (reset! result* result)
                                result)))
      (input/dispatch! input-runtime node-id event)
      (let [result @result*]
        (when-not (= result EventResult/PASS)
          (swap! (state runtime) assoc :invalidated? true))
        result))))

(defn extract! [runtime ^FrameContext context]
  (let [snapshot @(state runtime)
        mounts (vals (:mounts snapshot))
        grouped (for [stage frame/stages
                      :let [commands (mapcat (fn [mount]
                                               (concat
                                                 (get @(-> mount :commands) stage [])
                                                 (when (= stage (host-stage (:host mount)))
                                                   (render-mount mount context snapshot))))
                                             mounts)]
                      :when (seq commands)]
                  [stage commands])]
    (swap! (state runtime) assoc :invalidated? false)
    (let [^FramePacket ui-packet (frame/packet (.frameId context) grouped)
          effect-passes (effects/extract-passes (:effects snapshot))]
      (FramePacket. (.frameId ui-packet)
                    (into (vec (.passes ui-packet)) effect-passes)))))

(defn effect-runtime [runtime]
  (:effects @(state runtime)))

(defn spawn-effect! [runtime template-id owner params now-ms]
  (effects/spawn! (effect-runtime runtime) template-id owner params now-ms))

(defn destroy-effect! [runtime instance-id]
  (effects/destroy! (effect-runtime runtime) instance-id))

(defn clear-effect-owner! [runtime owner]
  (effects/clear-owner! (effect-runtime runtime) owner))

(defn tick-effects! [runtime delta-ms]
  (effects/tick! (effect-runtime runtime) delta-ms))

(defn reload-resources! [runtime generation]
  (effects/reload-resources! (effect-runtime runtime) generation)
  (swap! (state runtime) assoc :invalidated? true)
  generation)

(defn unmount! [runtime handle]
  (when-let [mount (get-in @(state runtime) [:mounts handle])]
    (when-let [model (:model mount)]
      (when (instance? AutoCloseable model)
        (.close ^AutoCloseable model)))
    (input/unregister-node! (:input @(state runtime)) (:input-node mount))
    (swap! (state runtime) update :mounts dissoc handle)
    (swap! (state runtime) assoc :invalidated? true))
  nil)

(defn unmount-all! [runtime]
  "Dispose every mount exactly once during host/world shutdown.

   The lifecycle owner calls this from a version-neutral bridge; it is kept
   here so platform code never needs to know MountHandle or ViewModel types."
  (doseq [handle (keys (:mounts @(state runtime)))]
    (unmount! runtime handle))
  nil)
