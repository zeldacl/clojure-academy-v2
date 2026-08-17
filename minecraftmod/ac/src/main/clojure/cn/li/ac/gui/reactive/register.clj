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
            [cn.li.ac.client.vfx-host :as vfx-host]
            [cn.li.ac.client.effect-controller :as effect-controller]
            [cn.li.presentation.core.host :as presentation-host]
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
(defonce ^:private template-cache* (atom {}))
(defonce ^:private combat-hud* (atom nil))
(defonce ^:private terminal* (atom nil))
(defonce ^:private effects-tick-installed* (atom false))

(def ^:private template-files
  {"academy:combat_hud" "combat_hud.ui.edn"
   "academy:terminal" "terminal.ui.edn"
   "academy:application" "application.ui.edn"
   "academy:machine_container" "machine_container.ui.edn"
   "academy:wireless_matrix" "wireless_matrix.ui.edn"
   "academy:wireless_node" "wireless_node.ui.edn"})

(defn- symbols-for
  "Derive a compiler symbol table straight from a ViewModel's own
   `binding-ids` (keyword -> id) and `action-ids` (id -> keyword) maps,
   instead of hand-copying a second literal table here that has to be kept
   numerically in sync by hand. A name/number typo in either now fails at
   compile-edn time (unknown binding/action) instead of silently drifting."
  [binding-ids action-ids]
  {:binding (into {} (map (fn [[k v]] [(name k) v])) binding-ids)
   :action (into {} (map (fn [[id k]] [(name k) id])) action-ids)})

(def ^:private template-symbols
  {"academy:combat_hud" (symbols-for presentation-hud/binding-ids presentation-hud/action-ids)
   "academy:terminal" (symbols-for presentation-terminal/binding-ids presentation-terminal/action-ids)
   "academy:application" (symbols-for presentation-application/binding-ids presentation-application/action-ids)
   "academy:machine_container" (symbols-for presentation-container/binding-ids presentation-container/action-ids)
   "academy:wireless_matrix" (symbols-for presentation-container/binding-ids presentation-container/action-ids)
   "academy:wireless_node" (symbols-for presentation-container/binding-ids presentation-container/action-ids)})

(defn- resolve-template [template-id]
  (let [id (if (instance? cn.li.presentation.core.TemplateId template-id)
             (.value ^cn.li.presentation.core.TemplateId template-id)
             (str template-id))]
    (or (get @template-cache* id)
        (when-let [file (get template-files id)]
          (let [resource (io/resource (str "assets/academy/presentation/" file))]
            (when-not resource
              (throw (ex-info "Presentation template resource missing" {:template-id id :file file})))
            (let [compiled (presentation-compiler/compile-edn
                             (cn.li.presentation.core.TemplateId. id)
                             (slurp resource)
                             (get template-symbols id {}))]
              (swap! template-cache* assoc id compiled)
              compiled))))))

(defn- create-presentation-runtime []
  (let [runtime (presentation-host/create
                  {:template-resolver resolve-template
                   :template-renderer presentation-render/render-template})
        _resource-reader (fn [path]
                           (if-let [resource (io/resource path)]
                             (slurp resource)
                             (throw (ex-info "Presentation resource missing"
                                             {:resource path}))))]
    runtime))

(defn- ensure-combat-hud! [runtime player-uuid width height]
  (or @combat-hud*
      (let [vm (presentation-hud/mount-combat-hud!
                 runtime player-uuid width height {}
                 ;; combat-view-model already owns :selected-skill/:skill-wheel-open?
                 ;; (updates its own snapshot atom on dispatch); this callback is a
                 ;; pure observer hook for any future cross-module side effect.
                 (fn [action payload]
                   (log/debug "Combat HUD action " action " " payload)))]
        (or (compare-and-set! combat-hud* nil vm)
            @combat-hud*))))

;; Only :world-after-translucent (level effects) and :first-person (hand
;; effects) are ever emitted by effect-controller's sample-plan!/sample-hand!
;; today; extend this if a new stage is wired into VFX sampling.
(def ^:private vfx-stage->render-stage
  {:world-after-translucent RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person RenderStage/FIRST_PERSON})

(defn- batch->render-command [batch]
  (RenderCommand$Batch.
    (or (get vfx-stage->render-stage (:stage batch))
        (throw (ex-info "unmapped VFX stage for Presentation frame merge"
                        {:stage (:stage batch)})))
    (name (:primitive batch))
    (some-> (:material batch) name)
    (some-> (:variant batch) name)
    (long (or (:layout-version batch) 1))
    (long (:count batch))
    (name (or (:sort-mode batch) :stable))
    (:payload batch)))

(defn- vfx-render-passes [vfx-context frame-id partial-tick]
  (let [frame (effect-controller/sample-frame!
                (merge vfx-context {:frame-id frame-id :partial-tick partial-tick}))]
    (for [[stage batches] (:stages frame)
          :when (seq batches)]
      (RenderPass. (get vfx-stage->render-stage stage) (mapv batch->render-command batches)))))

(defn- merge-vfx-passes
  "Fold VFX Core's sampled world/first-person batches into the same
   FramePacket the UI template interpreter produced, so a world-stage loader
   submits one packet through the unified pipeline instead of maintaining a
   second submission path through cn.li.platform.neutral.vfx.

   vfx-context is nil for HUD/Screen calls (they never pass a
   :presentation-context), so this is a no-op for the common case; only a
   world-stage submit-current-frame! call supplies one."
  [^FramePacket packet vfx-context frame-id partial-tick]
  (if-not vfx-context
    packet
    (try
      (let [extra (vfx-render-passes vfx-context frame-id partial-tick)]
        (if (seq extra)
          (FramePacket. (.frameId packet) (into (vec (.passes packet)) extra))
          packet))
      (catch Throwable throwable
        (log/error "VFX-to-Presentation frame merge failed" throwable)
        packet))))

(defn- ensure-terminal! [runtime owner dispatch-action!]
  (or @terminal*
      (let [vm (presentation-terminal/mount-terminal!
                 runtime owner dispatch-action!)]
        (or (compare-and-set! terminal* nil vm)
            @terminal*))))

(defn presentation-runtime
  "Return the single client Presentation Runtime for the current Framework
   lifetime. Loader code receives it as an opaque bridge value."
  []
  (or @presentation-runtime*
      (let [runtime (create-presentation-runtime)]
        (or (compare-and-set! presentation-runtime* nil runtime)
            @presentation-runtime*))))

(defn presentation-host-api
  "Opaque bridge contract consumed by platform/base and loader callbacks.

   The map deliberately contains functions rather than Presentation Core
   classes. Platform code can extract and dispose frames without depending on
   presentation-core; AC remains the only owner of the typed runtime object."
  []
  (let [runtime (presentation-runtime)]
    {:mount! (fn [id kind template model]
               (presentation-host/mount-host! runtime id kind template model))
     :frame! (fn [frame-id delta-seconds width height]
               (when-let [refresh! (:refresh! @combat-hud*)]
                 (refresh! width height {}))
               (when-let [refresh! (:refresh! @terminal*)]
                 (refresh!))
               (presentation-host/frame! runtime frame-id delta-seconds width height))
     ;; Called for every stage (HUD/Screen/world/...); vfx-context is nil
     ;; except for a world-stage submit-current-frame! call, so this behaves
     ;; exactly like :frame! above for HUD/Screen and additionally folds VFX
     ;; Core's sampled world/first-person batches in for the world stage.
     :frame-with-context! (fn [frame-id delta-seconds width height vfx-context]
                            (when-let [refresh! (:refresh! @combat-hud*)]
                              (refresh! width height {}))
                            (when-let [refresh! (:refresh! @terminal*)]
                              (refresh!))
                            (-> (presentation-host/frame! runtime frame-id delta-seconds width height)
                                (merge-vfx-passes vfx-context frame-id delta-seconds)))
     :mount-combat-hud! (fn [player-uuid width height]
                          (ensure-combat-hud! runtime player-uuid width height))
     :mount-terminal! (fn [owner dispatch-action!]
                        (:mount (ensure-terminal! runtime owner dispatch-action!)))
     :mount-application! (fn [owner title snapshot dispatch-action! on-close]
                           (:mount (presentation-application/mount!
                                     owner title snapshot dispatch-action! on-close)))
     :mount-container! (fn [menu-bridge snapshot-fn dispatch-action!]
                         (:mount (presentation-container/mount-container!
                                   runtime menu-bridge snapshot-fn dispatch-action!)))
     :unmount! (fn [mount]
                  (presentation-host/unmount! runtime mount)
                  (when (= mount (:mount @combat-hud*))
                    (reset! combat-hud* nil))
                  (when (= mount (:mount @terminal*))
                    (reset! terminal* nil)))
     :reload-resources! (fn [generation]
                          (reset! template-cache* {})
                          (vfx-host/reload-resources! generation))
     :dispatch! (fn [mount event]
                  (.dispatch ^cn.li.presentation.core.PresentationRuntime
                             (:api runtime) mount event))
     :dispatch-input! (fn [mount event]
                        (let [result (presentation-host/dispatch-input!
                                       runtime mount (presentation-input-event event))]
                          (cond
                            (= result cn.li.presentation.core.EventResult/CONSUME) :consume
                            (= result cn.li.presentation.core.EventResult/CAPTURE_POINTER) :capture-pointer
                            :else :pass)))
     :set-input-handler! (fn [mount handler]
                           (presentation-host/set-input-handler! runtime mount handler))
     :unmount-all! (fn []
                     (reset! combat-hud* nil)
                     (reset! terminal* nil)
                     (presentation-host/unmount-all! runtime))}))

(defn install-bridge!
  "Install the Presentation Runtime bridge into the neutral client boundary."
  []
  (let [api (presentation-host-api)]
    (bridge/merge-client-bridge!
      {:presentation-runtime presentation-runtime
       :presentation-host-api presentation-host-api
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
