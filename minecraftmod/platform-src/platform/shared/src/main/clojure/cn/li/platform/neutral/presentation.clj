(ns cn.li.platform.neutral.presentation
  "Loader-facing Presentation Runtime seam.

   This namespace owns no UI, layout, rendering, or game logic. It only
   connects the bootstrap-cached opaque AC host API to minecraft/base's lifecycle
   registry and returns opaque frame packets to version-owned callbacks."
  (:require [cn.li.mcbase.presentation.host-lifecycle :as lifecycle]))

(def ^:private host-id :presentation)
(def ^:private host-kind :unified)
(defonce ^:private frame-sequence* (atom 0))
(defonce ^:private last-frame-nanos* (atom 0))

;; Installed once by AC's client bootstrap. Render/tick paths read these direct
;; Var roots rather than walking the Framework or lifecycle host maps.
(def ^:private presentation-api nil)
(def ^:private backend-api nil)

;; HUD, Screen and (once wired) world/VFX submissions each call
;; dispatch-current-frame!/submit-current-frame! independently within the
;; same real render frame. Minting a fresh frame id per *call* defeated
;; extract!'s per-frame memoization outright — every stage recomputed every
;; mount's template from scratch. Real render frames are >=1ms apart even
;; at very high framerates; two stage submissions for the same real frame
;; land microseconds apart. Coalesce anything inside that window onto one
;; id instead of requiring every loader call site to share an explicit
;; "begin frame" token.
(def frame-coalesce-window-nanos 1000000)

(defn coalesce-frame-id
  "Pure decision: does `now` (nanoTime) belong to the same real frame as the
   last call at `last-nanos`? Returns [same-frame? next-last-nanos]."
  [now last-nanos]
  (if (< (- now last-nanos) frame-coalesce-window-nanos)
    [true last-nanos]
    [false now]))

(defn- current-frame-id! []
  (let [now (System/nanoTime)
        [same-frame? next-nanos] (coalesce-frame-id now @last-frame-nanos*)]
    (reset! last-frame-nanos* next-nanos)
    (if same-frame?
      @frame-sequence*
      (swap! frame-sequence* inc))))

(defn install-host!
  "Install the immutable AC Presentation host API during client bootstrap.

   The lifecycle registry keeps the same map for cleanup/introspection, while
   render and input paths use the direct Var root below."
  [api]
  (when-not (map? api)
    (throw (ex-info "presentation host API must be a map" {:value api})))
  (alter-var-root #'presentation-api (constantly api))
  (lifecycle/register-runtime! (lifecycle/shared) host-id host-kind api)
  api)

(defn reset-host-for-test! []
  (alter-var-root #'presentation-api (constantly nil))
  nil)

(defn- host-api [] presentation-api)

(defn ensure-registered!
  "Ensure the direct host is visible to lifecycle cleanup/introspection.

   This function is called during client setup; frame extraction never calls
   it, so lifecycle host-map access cannot occur on a render hot path."
  []
  (let [registry (lifecycle/shared)]
    (when presentation-api
      (lifecycle/register-runtime! registry host-id host-kind presentation-api))
    registry))

(defn frame!
  "Extract one immutable frame from AC's runtime, if installed.

   The return value is opaque to platform neutral code. Version backends may
   submit it only through their own mapped adapter."
  [frame-id delta-seconds width height]
  (when-let [frame! (:frame! (host-api))]
    (frame! frame-id delta-seconds width height)))

(defn ensure-combat-hud!
  [player-uuid width height]
  (when-let [mount! (:mount-combat-hud! (host-api))]
    (mount! player-uuid width height)))

(defn mount-terminal!
  "Mount a Terminal Screen through the opaque AC host API.

   The returned value is an opaque mount token; terminal state, text input and
   modal semantics remain owned by AC/Presentation Runtime."
  [owner dispatch-action!]
  (when-let [mount! (:mount-terminal! (host-api))]
    (mount! owner dispatch-action!)))

(defn mount-container!
  "Mount a Menu/Slot presentation without exposing the server menu model."
  [menu-bridge snapshot-fn dispatch-action!]
  (when-let [mount! (:mount-container! (host-api))]
    (mount! menu-bridge snapshot-fn dispatch-action!)))

(defn unmount! [mount]
  "Dispose one opaque mount token from a Screen/host lifecycle callback."
  (when-let [unmount! (:unmount! (host-api))]
    (unmount! mount))
  nil)

(defn dispatch-input!
  "Forward a normalized input map through the opaque AC host API.

   Version Screen boundaries never construct Presentation Core event classes;
   they only provide {:type ...} data and Minecraft-native coordinates."
  [mount event]
  (when-let [dispatch! (:dispatch-input! (host-api))]
    (dispatch! mount event)))

(defn- as-stage-result
  "Normalize host frame extraction to {:stage :frame}.

   `:frame!` returns a raw packet (wrapped by dispatch-runtime-stage!);
   `:frame-with-context!` must return the same envelope. Tolerate a bare
   packet so a mismatched host API cannot submit a nil stage."
  [stage result]
  (cond
    (nil? result) nil
    (and (map? result) (contains? result :frame)) result
    :else {:stage stage :frame result}))

(defn dispatch-stage-with-context!
  [stage frame-id delta-seconds width height context]
  (when-let [api (host-api)]
    (as-stage-result
     stage
     (if-let [frame-with-context! (:frame-with-context! api)]
       (frame-with-context! stage frame-id delta-seconds width height context)
       (when-let [frame! (:frame! api)]
         {:host-id host-id
          :stage stage
          :frame (frame! frame-id delta-seconds width height)})))))

(defn dispatch-stage!
  [stage frame-id delta-seconds width height]
  (dispatch-stage-with-context! stage frame-id delta-seconds width height nil))

(defn dispatch-current-frame!
  [stage delta-seconds width height]
  (let [result (dispatch-stage-with-context! stage (current-frame-id!)
                                             delta-seconds width height nil)]
    ;; Backend submission is intentionally an opaque callback.  The neutral
    ;; seam never inspects FramePacket or imports presentation-core; a mapped
    ;; mc-* backend decides how to consume the packet for its Minecraft API.
    (when-let [submit! (:submit! backend-api)]
      (when result
        (submit! (:stage result) (:frame result))))
    result))

(defn submit-current-frame!
  "Extract and submit one stage with an opaque version-owned render context.

   The context is normally GuiGraphics/GuiGraphicsExtractor. It never enters
   mcbase or the Runtime API and is consumed only by the matching mc-* backend."
  [stage delta-seconds width height render-context]
  (let [frame-context (when (map? render-context)
                        (:presentation-context render-context))
        backend-context (if (and (map? render-context)
                                 (contains? render-context :backend-context))
                          (:backend-context render-context)
                          render-context)
        result (dispatch-stage-with-context! stage (current-frame-id!)
                                              delta-seconds width height frame-context)]
    (when-let [submit! (:submit! backend-api)]
      (when result
        (submit! (:stage result) (:frame result) backend-context)))
    result))

(defn register-backend!
  "Install the version-owned backend callback for this client target.

   Loader code only performs this registration at init; all frame extraction
   and lifecycle state remain in the opaque AC host API."
  [backend]
  (when-not (fn? (:submit! backend))
    (throw (ex-info "presentation backend must expose :submit!" {})))
  (alter-var-root #'backend-api (constantly backend))
  backend)

(defn backend [] backend-api)

(defn reload-resources! [generation]
  (when-let [reload! (:reload-resources! (host-api))]
    (reload! generation))
  generation)

(defn shutdown! []
  (when-let [unmount-all! (:unmount-all! (host-api))]
    (unmount-all!))
  nil)

;; Presentation Runtime opaque API. These functions intentionally traffic
;; only in maps and opaque mount tokens; platform code does not import core
;; classes or the build-time compiler.
(defn mount-view! [spec]
  (when-let [mount! (:mount-view! (host-api))]
    (mount! spec)))

(defn present-view! [mount state]
  (when-let [present! (:present-view! (host-api))]
    (present! mount state)))

(defn update-host! [mount geometry]
  (when-let [update! (:update-host! (host-api))]
    (update! mount geometry)))

(defn extract-stage! [stage frame-context]
  (when-let [extract! (:extract-stage! (host-api))]
    (extract! stage frame-context)))

(defn semantics! [mount]
  (when-let [semantics (:semantics! (host-api))]
    (semantics mount)))

(defn invalidate-render-resources! []
  (when-let [invalidate! (:invalidate-render-resources!
                          (host-api))]
    (invalidate!)))