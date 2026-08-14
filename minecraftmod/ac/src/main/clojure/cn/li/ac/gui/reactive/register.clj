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
            [cn.li.ac.client.vfx-runtime :as effect-controller]
            [cn.li.presentation.core.host :as presentation-host]
            [cn.li.presentation.core.frame :as presentation-frame]
            [cn.li.presentation.core.export :as presentation-export]
            [cn.li.presentation.core.effects :as effects-runtime]
            [cn.li.presentation.compiler.core :as presentation-compiler]
            [cn.li.presentation.compiler.fx :as presentation-fx]
            [cn.li.presentation.compiler.render :as presentation-render]
            [cn.li.mcmod.util.log :as log]))

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

(def ^:private effect-template-files
  {"academy:body_intensify" "body_intensify.fx.edn"
   "academy:body_intensify_burst" "body_intensify_burst.fx.edn"
   "academy:railgun_charge" "railgun_charge.fx.edn"})

(defn- append-effect-geometry
  [packet frame-id context]
  (if-not (and (map? context) (:camera-pos context))
    packet
    (let [sample (when (effect-controller/active?)
                 (effect-controller/sample-frame!
                   {:frame-id frame-id
                    :tick (:tick context)
                    :camera-pos (:camera-pos context)
                    :hand-center-pos (:hand-center-pos context)
                    :query-nearby-blocks-fn (:query-nearby-blocks-fn context)}))
        batches (get-in sample [:stages :world-after-translucent])
        plans (keep #(-> % :payload first) batches)
          ops (vec (mapcat #(or (:ops %) []) plans))
          walk-speed (reduce (fn [current plan]
                               (let [candidate (:local-walk-speed plan)]
                                 (if (number? candidate)
                                   (if (number? current)
                                     (min (double current) (double candidate))
                                     (double candidate))
                                   current)))
                             nil plans)]
      (when-let [sample-frame-id (:frame-id sample)]
        (effect-controller/release-frame! sample-frame-id))
      (if (seq ops)
        (presentation-frame/append-mesh-payload
          packet cn.li.presentation.core.RenderStage/WORLD_AFTER_TRANSLUCENT
          {:ops ops
           :local-walk-speed (when (number? walk-speed) (float walk-speed))
           :camera-pos (:camera-pos context)})
        packet))))

(def ^:private template-symbols
  {"academy:combat_hud"
   {:binding {"cp-ratio" 0 "overload-ratio" 1 "skills" 2
              "selected-skill" 3 "cooldowns" 4 "crosshair" 5
              "screen-flash-alpha" 6}
    :action {"combat/select-skill" 0 "combat/toggle-skill-wheel" 1}}
   "academy:terminal"
   {:binding {"installed?" 0 "apps" 1 "page" 2 "loading?" 3
              "query" 4 "modal" 5}
    :action {"terminal/set-page" 0 "terminal/query" 1
             "terminal/install-app" 2 "terminal/uninstall-app" 3
             "terminal/submit-query" 4 "terminal/close-modal" 5}}
   "academy:application"
    {:binding {"title" 0 "lines" 1 "status" 2 "scroll" 3 "modal" 4
              "button-left" 5 "button-right" 6 "input" 7}
    :action {"application/left" 0 "application/right" 1
             "application/activate" 2 "application/delete" 3}}
   "academy:machine_container"
   {:binding {"slots" 0 "anchors" 1 "energy-ratio" 2 "progress-ratio" 3 "machine-state" 4
              "button-left" 5 "button-right" 6 "network-state" 7
              "network-owner" 8 "network-range" 9 "network-bandwidth" 10
              "network-load" 11 "network-ssid" 12 "network-password" 13
              "node-name" 14}
    :action {"container/click-slot" 0 "container/quick-move" 1
             "container/button" 2}}
   "academy:wireless_matrix"
   {:binding {"slots" 0 "anchors" 1 "energy-ratio" 2 "progress-ratio" 3
              "machine-state" 4 "button-left" 5 "button-right" 6
              "network-state" 7 "network-owner" 8 "network-range" 9
              "network-bandwidth" 10 "network-load" 11 "network-ssid" 12
              "network-password" 13}
    :action {"container/click-slot" 0 "container/quick-move" 1 "container/button" 2}}
   "academy:wireless_node"
   {:binding {"slots" 0 "anchors" 1 "energy-ratio" 2 "progress-ratio" 3
              "machine-state" 4 "button-left" 5 "button-right" 6
              "network-state" 7 "network-owner" 8 "network-range" 9
              "network-bandwidth" 10 "network-load" 11 "network-ssid" 12
              "network-password" 13 "node-name" 14}
    :action {"container/click-slot" 0 "container/quick-move" 1 "container/button" 2}}})

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
    (doseq [[effect-id file] effect-template-files]
      (when-let [resource (io/resource (str "assets/academy/presentation/" file))]
        (let [compiled (presentation-fx/compile-edn (slurp resource))]
          (effects-runtime/register-template!
            (presentation-host/effect-runtime runtime)
            (assoc compiled :id (keyword (last (string/split effect-id #":"))))))))
    runtime))

(defn- ensure-combat-hud! [runtime player-uuid width height]
  (or @combat-hud*
      (let [vm (presentation-hud/mount-combat-hud!
                 runtime player-uuid width height {}
                 (fn [_action _payload] nil))]
        (or (compare-and-set! combat-hud* nil vm)
            @combat-hud*))))

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
               (some-> (presentation-host/frame! runtime frame-id delta-seconds width height)
                       presentation-export/neutral-frame))
     :frame-with-context! (fn [frame-id delta-seconds width height context]
                            (let [packet (presentation-host/frame!
                                           runtime frame-id delta-seconds width height)]
                              (-> packet
                                  (append-effect-geometry frame-id (or context {}))
                                  presentation-export/neutral-frame)))
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
                          (presentation-host/reload-resources! runtime generation))
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
     :presentation-spawn-effect! (fn [template-id owner params now-ms]
                                   (presentation-host/spawn-effect!
                                     runtime template-id owner params now-ms))
     :presentation-destroy-effect! (fn [instance-id]
                                     (presentation-host/destroy-effect! runtime instance-id))
     :presentation-clear-effect-owner! (fn [owner]
                                         (presentation-host/clear-effect-owner! runtime owner))
     :presentation-tick-effects! (fn [delta-ms]
                                   (presentation-host/tick-effects! runtime delta-ms))
     :presentation-fov-offset effect-controller/current-fov-offset
     :presentation-hand-transform effect-controller/current-hand-transform
     :presentation-drain-camera-pitch-deltas! effect-controller/drain-camera-pitch-deltas!
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
       :presentation-spawn-effect! (:presentation-spawn-effect! api)
       :presentation-destroy-effect! (:presentation-destroy-effect! api)
       :presentation-clear-effect-owner! (:presentation-clear-effect-owner! api)
       :presentation-tick-effects! (:presentation-tick-effects! api)
       :presentation-fov-offset (:presentation-fov-offset api)
       :presentation-hand-transform (:presentation-hand-transform api)
       :presentation-drain-camera-pitch-deltas! (:presentation-drain-camera-pitch-deltas! api)}))
  (when (compare-and-set! effects-tick-installed* false true)
    (content-actions/register-client-tick-hook!
      #(do
         (when-let [runtime @presentation-runtime*]
           (presentation-host/tick-effects! runtime 50))
         ;; The remaining ability descriptors are sampled through the same
         ;; Presentation frame bridge while their templates are converted.
         (effect-controller/tick!))))
  (log/info "Presentation Runtime bridge installed"))
