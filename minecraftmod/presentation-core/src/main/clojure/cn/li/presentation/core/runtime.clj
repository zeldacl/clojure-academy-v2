(ns cn.li.presentation.core.runtime
  (:require [cn.li.presentation.core.transaction :as tx]
            [cn.li.presentation.core.frame :as frame]
            [cn.li.presentation.core.input :as input])
  (:import [cn.li.presentation.core HostDescriptor MountHandle FrameContext
            PresentationInputEvent PresentationInputEvent$Pointer PresentationInputEvent$Pointer$Type
            PresentationInputEvent$Key PresentationInputEvent$CharacterInput PresentationInputEvent$Scroll
            EventResult]
           [cn.li.mcmod.runtime RenderStage]
           [java.lang AutoCloseable]))

(defn create-runtime
  ([] (create-runtime {}))
  ([options]
   (let [runtime (atom {:mounts {} :next-id 1 :invalidated? true
                        :input (input/create)
                        :template-resolver (:template-resolver options)
                        :template-renderer (:template-renderer options)
                        :last-frame-id nil :last-frame nil})]
      (assoc @runtime
             :state runtime
             :tx (tx/scheduler #(swap! runtime assoc :invalidated? true))))))

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

(defn- host-stage ^RenderStage [^HostDescriptor host]
  (case (str (some-> host .kind))
    "HUD" RenderStage/HUD
    "WORLD_UI" RenderStage/WORLD_AFTER_TRANSLUCENT
    "SCREEN" RenderStage/SCREEN
    RenderStage/HUD))
(defn mount! [runtime host template model]
  (let [id (:next-id (swap! (state runtime) update :next-id inc))
        handle (MountHandle. (dec id))]
    (swap! (state runtime) assoc-in [:mounts handle]
           {:host host :template template :model model
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

(defn transact! [runtime mutation!] (tx/transact! (:tx runtime) mutation!))

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
    ;; The mount's input node is already registered once, in mount!, with a
    ;; handler that reads [:mounts handle :handler] fresh from state on every
    ;; invocation -- it doesn't need (and must not get) a second, per-call
    ;; registration here. input/dispatch! now returns the real EventResult
    ;; directly, so there's no need to smuggle it out through a side-channel
    ;; atom captured by a throwaway re-registered closure either.
    (let [input-runtime (:input @(state runtime))
          node-id (:input-node mount)
          event (normalize-input-event event)
          result (input/dispatch! input-runtime node-id event)]
      (when-not (= result EventResult/PASS)
        (swap! (state runtime) assoc :invalidated? true))
      result)))

(defn- extract-uncached [runtime ^FrameContext context]
  (let [snapshot @(state runtime)
        mounts (vals (:mounts snapshot))
        grouped (for [stage frame/stages
                      :let [commands (mapcat
                                       (fn [mount]
                                         (when (= stage (host-stage (:host mount)))
                                           (render-mount mount context snapshot)))
                                       mounts)]
                      :when (seq commands)]
                  [stage commands])]
    (frame/packet (.frameId context) grouped)))

(defn extract!
  "Extract the FramePacket for one real frame, memoized by :frameId.

   Multiple stages (HUD, Screen, world, ...) each ask for a frame within the
   same real render frame; recomputing every mount's template on each of
   those calls is the difference between the documented per-frame CPU/byte
   budget and blowing through it the moment a container screen is open
   alongside the HUD. A repeat call with the same frame id and no
   intervening mutation returns the cached packet; anything that mutates
   mount state (mount!/unmount!/dispatch! consuming the event/tx flush)
   clears :invalidated? to true and forces a rebuild even for the same id."
  [runtime ^FrameContext context]
  (let [frame-id (.frameId context)
        snapshot @(state runtime)]
    (if (and (= frame-id (:last-frame-id snapshot))
             (not (:invalidated? snapshot))
             (:last-frame snapshot))
      (:last-frame snapshot)
      (let [packet (extract-uncached runtime context)]
        (swap! (state runtime) assoc
               :invalidated? false
               :last-frame-id frame-id
               :last-frame packet)
        packet))))

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
