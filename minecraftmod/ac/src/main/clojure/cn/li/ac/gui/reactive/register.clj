(ns cn.li.ac.gui.reactive.register
  "Reactive GUI bridge — installs reactive handlers via client bridge merge.
   Individual block GUIs self-register via their own init-*-reactive! functions."
  (:require
            [cn.li.ability.compose :as ability-compose]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.ability.client.presentation-hud :as presentation-hud]
            [cn.li.ac.terminal.client.presentation-terminal :as presentation-terminal]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.gui.presentation-application :as presentation-application]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.presentation.core.host :as presentation-host]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.runtime FramePacket RenderPass RenderStage]))

(defonce ^:private presentation-runtime* (atom nil))

(defn presentation-runtime
  "The retained Runtime instance exposed through the generic host map." 
  []
  (or @presentation-runtime*
      (let [runtime (presentation-host/create-runtime)]
        (or (compare-and-set! presentation-runtime* nil runtime)
            @presentation-runtime*))))

(defonce ^:private combat-hud* (atom nil))
(defonce ^:private terminal* (atom nil))


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
(defn- core-host-api []
  (presentation-host/api (presentation-runtime)))

(def ^:private stage->render-stage
  {:world-before-translucent RenderStage/WORLD_BEFORE_TRANSLUCENT
   :world-after-translucent RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person RenderStage/FIRST_PERSON
   :hud-underlay RenderStage/HUD_UNDERLAY
   :hud RenderStage/HUD
   :hud-overlay RenderStage/HUD_OVERLAY
   :screen RenderStage/SCREEN
   :post-process RenderStage/POST_PROCESS})

(defn- frame-packet
  [frame-id stage frame-context]
  (let [api (presentation-host-api)
        extracted ((:extract-stage! api) stage frame-context)
        contributors (mapv (fn [[index mount]]
                             (ability-compose/contributor
                              (keyword (format "mount-%08d" index))
                              (fn [_] (:commands mount))))
                           (map-indexed vector (:mounts extracted)))
        composed (ability-compose/compose-frame
                  {:player-id :client
                   :max-render-commands-per-frame 8192}
                  contributors
                  {:frame-seq frame-id
                   :stage stage
                   :frame-context frame-context})
        commands (:commands composed)]
    (FramePacket. (long frame-id)
                  [(RenderPass. (or (get stage->render-stage stage)
                                    RenderStage/SCREEN)
                                commands)])))

(defn presentation-host-api
  "Single AC host contract. All view mounts and frame extraction use Runtime;
   only normalized artifact IDs and neutral runtime maps cross the bridge."
  []
  (let [runtime (presentation-runtime)
        core-api (core-host-api)]
     {:mount! (fn [owner host-kind view-id model]
               (presentation/mount-view!
                {:view-id (or view-id :academy.app/application)
                 :host-kind host-kind
                 :state (if (map? model) model {})
                 :dispatch-action! (fn [_ _ current] current)
                 :on-close nil}))
     :frame! (fn [frame-id _delta-seconds width height]
               (when-let [refresh! (:refresh! @combat-hud*)]
                 (refresh! width height {}))
               (when-let [refresh! (:refresh! @terminal*)]
                 (refresh!))
               (frame-packet frame-id :screen {:width width :height height}))
     :frame-with-context! (fn [frame-id delta-seconds width height vfx-context]
                            (let [frame (frame-packet frame-id :screen
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
                 ((:unmount! core-api) mount)
                 (when (= mount (:mount @combat-hud*))
                   (reset! combat-hud* nil))
                 (when (= mount (:mount @terminal*))
                   (reset! terminal* nil)))
     :reload-resources! (fn [_generation]
                          ((:invalidate-render-resources! core-api)))
     :dispatch! (fn [mount event]
                  ((:dispatch-input! core-api) mount event))
     :dispatch-input! (fn [mount event]
                        ((:dispatch-input! core-api) mount event))
     :unmount-all! (fn []
                     (reset! combat-hud* nil)
                     (reset! terminal* nil)
                     ((:unmount-all! core-api)))}))

(defn install-bridge!
  "Install the Presentation Runtime bridge into the neutral client boundary."
  []
  (bridge/merge-client-bridge!
    {:presentation-host-api presentation-host-api})
  (log/info "Presentation Runtime bridge installed"))




