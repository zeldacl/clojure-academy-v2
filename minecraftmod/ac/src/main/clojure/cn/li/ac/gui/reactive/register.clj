(ns cn.li.ac.gui.reactive.register
  "Reactive GUI bridge — installs reactive handlers via client bridge merge.
   Individual block GUIs self-register via their own init-*-reactive! functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.ac.ability.client.presentation-hud :as presentation-hud]
            [cn.li.ac.terminal.client.presentation-terminal :as presentation-terminal]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.gui.presentation-application :as presentation-application]
            [cn.li.ac.gui.presentation-v2 :as presentation-v2]
            [cn.li.ac.client.vfx-host :as vfx-host]
            [cn.li.ac.client.effect-controller :as effect-controller]
            [cn.li.presentation.core.host :as presentation-host]
            [cn.li.presentation.core.host-v2 :as presentation-host-v2]
            [cn.li.presentation.compiler.core :as presentation-compiler]
            [cn.li.presentation.compiler.render :as presentation-render]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.runtime FramePacket RenderPass RenderCommand$Batch RenderStage]))

(defn- presentation-input-event [event]
  (let [{:keys [type]} event]
    (case type
      :pointer (cn.li.presentation.core.PresentationInputEvent$Pointer.
                 (case (:event-type event)
                   :move cn.li.presentation.core.PresentationInputEvent$Pointer$Type/MOVE
                   :down cn.li.presentation.core.PresentationInputEvent$Pointer$Type/DOWN
                   :up cn.li.presentation.core.PresentationInputEvent$Pointer$Type/UP)
                 (float (:x event 0.0)) (float (:y event 0.0)) (int (:button event -1)))
      :key (cn.li.presentation.core.PresentationInputEvent$Key.
             (int (:key-code event)) (boolean (:pressed? event))
             (boolean (:shift? event)) (boolean (:control? event))
             (boolean (:alt? event)))
      :character (cn.li.presentation.core.PresentationInputEvent$CharacterInput.
                   (str (:text event "")) (boolean (:composing? event)))
      :scroll (cn.li.presentation.core.PresentationInputEvent$Scroll.
                (float (:x event 0.0)) (float (:y event 0.0)))
      (throw (ex-info "Unknown presentation input event" {:event event})))))

(defonce ^:private presentation-runtime* (atom nil))
(defonce ^:private presentation-runtime-v2* (atom nil))

(defn presentation-runtime-v2
  "The retained Runtime v2 instance exposed through the generic host map." 
  []
  (or @presentation-runtime-v2*
      (let [runtime (presentation-host-v2/create-runtime)]
        (or (compare-and-set! presentation-runtime-v2* nil runtime)
            @presentation-runtime-v2*))))

(defonce ^:private combat-hud* (atom nil))
(defonce ^:private terminal* (atom nil))

(defn presentation-runtime []
  (presentation-runtime-v2))

(defn- ensure-combat-hud! [runtime player-uuid width height]
  (or @combat-hud*
      (let [vm (presentation-hud/mount-combat-hud!
                 runtime player-uuid width height {}
                 (fn [_action _payload] nil))]
        (or (compare-and-set! combat-hud* nil vm)
            @combat-hud*))))

(defn- ensure-terminal! [runtime owner dispatch-action!]
  (or @terminal*
      (let [vm (presentation-terminal/mount-terminal! owner dispatch-action!)]
        (or (compare-and-set! terminal* nil vm)
            @terminal*))))

(defn- merge-vfx-passes
  [_vfx-context _frame-id _partial-tick packet]
  packet)
(defn presentation-host-api-v2 []
  (presentation-host-v2/api (presentation-runtime-v2)))

(def ^:private stage->render-stage
  {:world-before-translucent RenderStage/WORLD_BEFORE_TRANSLUCENT
   :world-after-translucent RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person RenderStage/FIRST_PERSON
   :hud-underlay RenderStage/HUD_UNDERLAY
   :hud RenderStage/HUD
   :hud-overlay RenderStage/HUD_OVERLAY
   :screen RenderStage/SCREEN
   :post-process RenderStage/POST_PROCESS})

(defn- v2-frame
  [frame-id stage frame-context]
  (let [api (presentation-host-api-v2)
        extracted ((:extract-stage! api) stage frame-context)
        commands (vec (mapcat :commands (:mounts extracted)))]
    (FramePacket. (long frame-id)
                  [(RenderPass. (or (get stage->render-stage stage)
                                    RenderStage/SCREEN)
                                commands)])))

(defn presentation-host-api
  "Single AC host contract. All view mounts and frame extraction use Runtime v2;
   no template resolver or interpreter is exposed across the bridge."
  []
  (let [runtime (presentation-runtime-v2)
        v2-api (presentation-host-api-v2)]
    {:mount! (fn [owner host-kind _view-id _model]
               (presentation-v2/mount-view!
                {:view-id :academy/app/application
                 :host-kind host-kind
                 :state (if (map? _model) _model {})
                 :dispatch-action! (fn [_ _ current] current)
                 :on-close nil}))
     :frame! (fn [frame-id _delta-seconds width height]
               (when-let [refresh! (:refresh! @combat-hud*)]
                 (refresh! width height {}))
               (when-let [refresh! (:refresh! @terminal*)]
                 (refresh!))
               (v2-frame frame-id :screen {:width width :height height}))
     :frame-with-context! (fn [frame-id delta-seconds width height vfx-context]
                            (let [frame (v2-frame frame-id :screen
                                                  {:width width :height height})]
                              (merge-vfx-passes vfx-context frame-id delta-seconds frame)))
     :mount-combat-hud! (fn [player-uuid width height]
                          (:mount (ensure-combat-hud! runtime player-uuid width height)))
     :mount-terminal! (fn [owner dispatch-action!]
                        (:mount (ensure-terminal! runtime owner dispatch-action!)))
     :mount-application! (fn [owner title snapshot dispatch-action! on-close]
                           (:mount (presentation-application/mount!
                                    owner title snapshot dispatch-action! on-close)))
     :mount-container! (fn [menu-bridge snapshot-fn dispatch-action!]
                         (:mount (presentation-container/mount-container!
                                  runtime menu-bridge snapshot-fn dispatch-action!)))
     :unmount! (fn [mount]
                 ((:unmount! v2-api) mount)
                 (when (= mount (:mount @combat-hud*))
                   (reset! combat-hud* nil))
                 (when (= mount (:mount @terminal*))
                   (reset! terminal* nil)))
     :reload-resources! (fn [_generation]
                          ((:invalidate-render-resources! v2-api)))
     :dispatch! (fn [mount event]
                  ((:dispatch-input! v2-api) mount event))
     :dispatch-input! (fn [mount event]
                        ((:dispatch-input! v2-api) mount event))
     :set-input-handler! (fn [_mount _handler]
                           (log/warn "Runtime v2 uses reducer routing; legacy input handler ignored"))
     :unmount-all! (fn []
                     (reset! combat-hud* nil)
                     (reset! terminal* nil)
                     ((:unmount-all! v2-api)))}))
(defn install-bridge!
  "Install the Presentation Runtime bridge into the neutral client boundary."
  []
  (let [api (presentation-host-api)]
    (bridge/merge-client-bridge!
      {:presentation-runtime presentation-runtime
       :presentation-host-api presentation-host-api
       :presentation-host-api-v2 presentation-host-api-v2
       ;; VFX Core's OWN host installation (tick!/fov-offset/hand transforms)
       ;; is still installed independently by ac.client.vfx-host — this
       ;; bridge never re-exports that host API key (verifyVfxDirectHostBoundary
       ;; enforces this). :frame-with-context! above does directly call
       ;; effect-controller's sample-frame! to fold world/first-person batches
       ;; into the same FramePacket the UI template interpreter produces, so
       ;; a world-stage
       ;; loader has one unified submission path instead of two.
       }))
  (log/info "Presentation Runtime bridge installed"))
