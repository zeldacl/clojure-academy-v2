(ns cn.li.platform.neutral.presentation
  "Loader-facing Presentation Runtime seam.

   This namespace owns no UI, layout, rendering, or game logic. It only
   connects the client bridge's opaque AC host API to minecraft/base's lifecycle
   registry and returns opaque frame packets to version-owned callbacks."
  (:require [cn.li.mcbase.presentation.host-lifecycle :as lifecycle]
            [cn.li.platform.neutral.client-runtime :as client-runtime]))

(def ^:private host-id :presentation)
(def ^:private host-kind :unified)
(defonce ^:private frame-sequence* (atom 0))
(defonce ^:private backend* (atom nil))

(defn ensure-registered!
  "Register AC's host API once the content bridge has been installed.

   Returns the shared lifecycle even when content is not present, allowing
   loaders to keep a stable callback path during title-screen/world changes."
  []
  (let [registry (lifecycle/shared)]
    (when-not (lifecycle/host-api registry host-id)
      (when-let [api (client-runtime/call-adapter :presentation-host-api)]
        (lifecycle/register-runtime! registry host-id host-kind api)))
    registry))

(defn frame!
  "Extract one immutable frame from AC's runtime, if installed.

   The return value is opaque to platform neutral code. Version backends may
   submit it only through their own mapped adapter."
  [frame-id delta-seconds width height]
  (let [registry (ensure-registered!)]
    (lifecycle/frame! registry host-id frame-id delta-seconds width height)))

(defn ensure-combat-hud!
  [player-uuid width height]
  (when-let [mount! (:mount-combat-hud! (lifecycle/host-api (ensure-registered!) host-id))]
    (mount! player-uuid width height)))

(defn mount-terminal!
  "Mount a Terminal Screen through the opaque AC host API.

   The returned value is an opaque mount token; terminal state, text input and
   modal semantics remain owned by AC/Presentation Runtime."
  [owner dispatch-action!]
  (when-let [mount! (:mount-terminal! (lifecycle/host-api (ensure-registered!) host-id))]
    (mount! owner dispatch-action!)))

(defn mount-container!
  "Mount a Menu/Slot presentation without exposing the server menu model."
  [menu-bridge snapshot-fn dispatch-action!]
  (when-let [mount! (:mount-container! (lifecycle/host-api (ensure-registered!) host-id))]
    (mount! menu-bridge snapshot-fn dispatch-action!)))

(defn unmount! [mount]
  "Dispose one opaque mount token from a Screen/host lifecycle callback."
  (when-let [unmount! (:unmount! (lifecycle/host-api (ensure-registered!) host-id))]
    (unmount! mount))
  nil)

(defn dispatch-input!
  "Forward a normalized input map through the opaque AC host API.

   Version Screen boundaries never construct Presentation Core event classes;
   they only provide {:type ...} data and Minecraft-native coordinates."
  [mount event]
  (when-let [dispatch! (:dispatch-input! (lifecycle/host-api
                                            (ensure-registered!) host-id))]
    (dispatch! mount event)))

(defn dispatch-stage!
  [stage frame-id delta-seconds width height]
  (let [registry (ensure-registered!)]
    (lifecycle/dispatch-runtime-stage!
      registry host-id stage frame-id delta-seconds width height)))

(defn dispatch-current-frame!
  [stage delta-seconds width height]
  (let [result (dispatch-stage! stage (swap! frame-sequence* inc)
                                delta-seconds width height)]
    ;; Backend submission is intentionally an opaque callback.  The neutral
    ;; seam never inspects FramePacket or imports presentation-core; a mapped
    ;; mc-* backend decides how to consume the packet for its Minecraft API.
    (when-let [submit! (:submit! @backend*)]
      (when result
        (submit! (:stage result) (:frame result))))
    result))

(defn submit-current-frame!
  "Extract and submit one stage with an opaque version-owned render context.

   The context is normally GuiGraphics/GuiGraphicsExtractor. It never enters
   mcbase or the Runtime API and is consumed only by the matching mc-* backend."
  [stage delta-seconds width height render-context]
  (let [result (dispatch-stage! stage (swap! frame-sequence* inc)
                                delta-seconds width height)]
    (when-let [submit! (:submit! @backend*)]
      (when result
        (submit! (:stage result) (:frame result) render-context)))
    result))

(defn register-backend!
  "Install the version-owned backend callback for this client target.

   Loader code only performs this registration at init; all frame extraction
   and lifecycle state remain in the opaque AC host API."
  [backend]
  (when-not (fn? (:submit! backend))
    (throw (ex-info "presentation backend must expose :submit!" {})))
  (reset! backend* backend)
  backend)

(defn backend [] @backend*)

(defn reload-resources! [generation]
  (lifecycle/reload-resources! (lifecycle/shared) generation))

(defn shutdown! []
  (let [registry (lifecycle/shared)]
    (when-let [unmount-all! (:unmount-all! (lifecycle/host-api registry host-id))]
      (unmount-all!))
    nil))
