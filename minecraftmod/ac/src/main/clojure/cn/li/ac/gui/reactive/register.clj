(ns cn.li.ac.gui.reactive.register
  "Reactive GUI bridge — installs reactive handlers via client bridge merge.
   Individual block GUIs self-register via their own init-*-reactive! functions."
  (:require
            [cn.li.ability.compose :as ability-compose]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.ability.client.presentation-hud :as presentation-hud]
            [cn.li.ac.terminal.client.apps.media-reactive :as media]
            [cn.li.ac.client.effect-controller :as effect-controller]
            [cn.li.ac.terminal.client.presentation-terminal :as presentation-terminal]
            [cn.li.ac.terminal.client.apps.tutorial-reactive :as tutorial-app]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.gui.presentation-application :as presentation-application]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.presentation.core.host :as presentation-host]
            [cn.li.mcmod.runtime.presentation-bridge :as presentation-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.runtime FramePacket RenderStage]
           [cn.li.mcmod.runtime.vfx VfxFrame]))

(defonce ^:private presentation-runtime* (atom nil))

(defn presentation-runtime
  "The retained Runtime instance exposed through the generic host map." 
  []
  (or @presentation-runtime*
      (let [runtime (presentation-host/create-runtime)]
        ;; compare-and-set! returns a boolean — return the runtime, not true/false.
        (if (compare-and-set! presentation-runtime* nil runtime)
          runtime
          @presentation-runtime*))))

(defonce ^:private combat-hud* (atom nil))
(defonce ^:private terminal* (atom nil))


(defn- ensure-combat-hud! [runtime player-uuid width height]
  (or @combat-hud*
      (let [vm (presentation-hud/mount-combat-hud!
                 runtime player-uuid width height {}
                 (fn [_action _payload] nil))]
        (if (compare-and-set! combat-hud* nil vm)
          vm
          @combat-hud*))))

(defn- ensure-terminal! [runtime owner dispatch-action!]
  (or @terminal*
      (let [vm (presentation-terminal/mount-terminal! owner dispatch-action!)]
        (if (compare-and-set! terminal* nil vm)
          vm
          @terminal*))))

(defonce ^:private vfx-sample-cache* (atom {:frame-id nil :vfx nil}))

(defn- sampled-vfx-frame!
  "Client-side VFX sampling stays here (it is stateful, version-frame-
   dependent sampling of live effect state, not a pure fold) - only the
   fold into RenderCommand/RenderPass (ability-compose/merge-vfx-into-frame)
   moved to ability-runtime, since that is the only module allowed to
   depend on both vfx-core and presentation-core.

   :frame-with-context! is the seam every stage submission goes through
   (world, first-person, HUD, screen, ...), and the neutral seam coalesces
   same-real-frame submissions onto one frame-id (see
   cn.li.platform.neutral.presentation/current-frame-id!) -- without this
   cache, a real frame with N eligible render stages would re-run VFX
   sampling (a full live-instance walk) N times instead of once."
  ^VfxFrame [frame-id partial-tick]
  (let [cache @vfx-sample-cache*]
    (if (= frame-id (:frame-id cache))
      (:vfx cache)
      (let [vfx (effect-controller/sample-java-frame! {:frame-id frame-id :partial-tick partial-tick})]
        (reset! vfx-sample-cache* {:frame-id frame-id :vfx vfx})
        vfx))))

(defn- core-host-api []
  (presentation-host/api (presentation-runtime)))

(defn- install-presentation-boundary!
  "Install the opaque Runtime API behind mcmod's version-neutral bridge.
   Minecraft/loader code can call the bridge; it never reaches Presentation
   implementation maps directly." 
  []
  (let [api (core-host-api)]
    (presentation-bridge/install-host!
     {:mount! (:mount! api)
      :sync! (:sync! api)
      :dispatch-input! (:dispatch-input! api)
      :begin-frame! (:begin-frame! api)
      :extract-stage! (:extract-stage! api)
      :unmount! (:unmount! api)})))

(def ^:private stage->render-stage
  {:world-before-translucent RenderStage/WORLD_BEFORE_TRANSLUCENT
   :world-after-translucent RenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person RenderStage/FIRST_PERSON
   :camera RenderStage/CAMERA
   :hud-underlay RenderStage/HUD_UNDERLAY
   :hud RenderStage/HUD
   :hud-overlay RenderStage/HUD_OVERLAY
   :screen RenderStage/SCREEN
   :post-process RenderStage/POST_PROCESS
   :audio RenderStage/AUDIO})

(declare presentation-host-api)


(defn- refresh-stage-state!
  [stage width height]
  (case stage
    :hud
    (do
      (media/refresh-active!)
      (when-let [refresh! (:refresh! @combat-hud*)]
        (refresh! width height {})))
    :screen
    (do
      (media/refresh-active!)
      (when-let [refresh! (:refresh! @terminal*)]
        (refresh!))
      (tutorial-app/screen-tick!))
    nil))

(defn- ui-by-stage-array ^objects [^RenderStage wanted-stage draw-list]
  (let [^objects arr (make-array cn.li.mcmod.runtime.ui.UiDrawList (alength (RenderStage/values)))]
    (when draw-list (aset arr (int (.ordinal wanted-stage)) draw-list))
    arr))

(defn- frame-packet
  "UI-only FramePacket for one stage's mounts, merged into a single
   UiDrawList (the zero-copy fast path when 0 or 1 mount is active, which
   is the overwhelmingly common case — Minecraft shows one Screen and one
   HUD at a time) and placed at that stage's slot in uiByStage."
  [frame-id stage frame-context]
  ;; Extract via the core Runtime API — presentation-host-api is the outer
  ;; AC wrapper and must not be consulted here (no :extract-stage!, and it
  ;; would rebuild the wrapper map every frame).
  (let [api (core-host-api)
        extracted ((:extract-stage! api) stage frame-context)
        draw-lists (keep :commands (:mounts extracted))
        merged (ability-compose/merge-draw-lists frame-id draw-lists)
        wanted (or (get stage->render-stage stage) RenderStage/SCREEN)]
    (FramePacket. (long frame-id) (ui-by-stage-array wanted merged) [])))

(defn presentation-host-api
  "Single AC host contract. All view mounts and frame extraction use Runtime;
   only normalized artifact IDs and neutral runtime maps cross the bridge."
  []
  (let [runtime (presentation-runtime)
        core-api (core-host-api)]
    ;; Merge core Runtime keys (:mount-view! :extract-stage! …) so AC adapters
    ;; and the platform neutral seam can reach them through one host map.
    (merge
     core-api
     {:mount! (fn [owner host-kind view-id model]
                (presentation/mount-view!
                 {:view-id (or view-id :academy.app/application)
                  :host-kind host-kind
                  :state (if (map? model) model {})
                  :dispatch-action! (fn [_ _ current] current)
                  :on-close nil}))
      :frame! (fn [frame-id _delta-seconds width height]
                (refresh-stage-state! :screen width height)
                ;; Raw FramePacket — dispatch-runtime-stage! wraps {:stage :frame}.
                (frame-packet frame-id :screen {:width width :height height}))
      :frame-with-context! (fn [stage frame-id delta-seconds width height _vfx-context]
                             (refresh-stage-state! stage width height)
                             (let [ui-packet (frame-packet frame-id stage
                                                           {:width width :height height})
                                   vfx (sampled-vfx-frame! frame-id delta-seconds)
                                   packet (ability-compose/merge-vfx-into-frame ui-packet vfx)]
                               ;; Same envelope as dispatch-runtime-stage!: the
                               ;; neutral seam submits (:stage result)/(:frame result).
                               (when packet
                                 {:stage stage :frame packet})))
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
                      ((:unmount-all! core-api)))})))

(defn install-bridge!
  "Install the Presentation Runtime host at the client bootstrap boundary."
  []
  (install-presentation-boundary!)
  (let [api (presentation-host-api)]
    ;; Keep one immutable map for both the AC UI adapter and neutral render
    ;; seam. The neutral seam caches the same map; no per-frame reconstruction.
    (bridge/merge-client-bridge!
      {:presentation-host-api (constantly api)})
    ((requiring-resolve
       'cn.li.platform.neutral.presentation/install-host!)
     api))
  (log/info "Presentation Runtime bridge installed"))



