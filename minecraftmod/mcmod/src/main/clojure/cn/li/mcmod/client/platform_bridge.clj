(ns cn.li.mcmod.client.platform-bridge
  "Platform-neutral client bridge injected by platform adapters.

  The bridge is intentionally content-agnostic: content modules choose screen
  and effect keys, while platform adapters provide generic host functions."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *client-bridge-ops* nil)

(defn- bridge-op [k & args]
  (when-let [ops *client-bridge-ops*]
    (when-let [f (get ops k)]
      (apply f args))))

(defn install-client-bridge!
  "Install client bridge callbacks from a map of handler functions."
  [{:keys [slot-key-down slot-key-tick slot-key-up slot-key-abort
           movement-key-down movement-key-tick movement-key-up
           open-screen open-simple-gui run-client-effect
           get-client-player screen-active? close-screen!
           send-system-message! get-mouse-pos game-time-ms font-width resolve-shader get-window-size draw-ops-host!
           register-font! get-player-owner font-text-width stop-all-media! has-recipes?
           send-to-client! spawn-item-stack-at!]}]
  (prt/install-impl! #'*client-bridge-ops*
                     {:slot-key-down slot-key-down
                      :slot-key-tick slot-key-tick
                      :slot-key-up slot-key-up
                      :slot-key-abort slot-key-abort
                      :movement-key-down movement-key-down
                      :movement-key-tick movement-key-tick
                      :movement-key-up movement-key-up
                      :open-screen open-screen
                      :open-simple-gui open-simple-gui
                      :run-client-effect run-client-effect
                      :get-client-player get-client-player
                      :screen-active? screen-active?
                      :close-screen! close-screen!
                      :send-system-message! send-system-message!
                      :get-mouse-pos get-mouse-pos
                      :game-time-ms game-time-ms
                      :font-width font-width
                      :resolve-shader resolve-shader
                      :get-window-size get-window-size
                      :draw-ops-host! draw-ops-host!
                      :register-font! register-font!
                      :get-player-owner get-player-owner
                      :font-text-width font-text-width
                      :stop-all-media! stop-all-media!
                      :has-recipes? has-recipes?
                      :send-to-client! send-to-client!
                      :spawn-item-stack-at! spawn-item-stack-at!}
                     "client-bridge")
  nil)

(defn client-bridge-available? []
  (prt/impl-available? #'*client-bridge-ops*))

(defn call-with-client-bridge [ops f]
  (binding [*client-bridge-ops* ops] (f)))

(defn reset-client-bridge-for-test!
  []
  (alter-var-root #'*client-bridge-ops* (constantly nil))
  nil)

(defn on-slot-key-down!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-down player-uuid key-idx)
      (log/debug "Client bridge slot-key-down not available")))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-tick player-uuid key-idx)
      (log/debug "Client bridge slot-key-tick not available")))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-up player-uuid key-idx)
      (log/debug "Client bridge slot-key-up not available")))

(defn on-slot-key-abort!
  [player-uuid key-idx]
  (or (bridge-op :slot-key-abort player-uuid key-idx)
      (log/debug "Client bridge slot-key-abort not available")))

(defn on-movement-key-down!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-down player-uuid movement-key)
      (log/debug "Client bridge movement-key-down not available")))

(defn on-movement-key-tick!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-tick player-uuid movement-key)
      (log/debug "Client bridge movement-key-tick not available")))

(defn on-movement-key-up!
  [player-uuid movement-key]
  (or (bridge-op :movement-key-up player-uuid movement-key)
      (log/debug "Client bridge movement-key-up not available")))

(defn open-screen!
  "Open a content-owned screen through the installed platform host."
  ([screen-key]
   (open-screen! screen-key nil))
  ([screen-key payload]
   (or (bridge-op :open-screen screen-key payload)
       (log/debug "Client bridge screen host not available" {:screen-key screen-key}))))

(defn open-simple-gui!
  ([gui-widget title]
   (or (bridge-op :open-simple-gui gui-widget title)
       (log/debug "Client bridge simple GUI not available")))
  ([gui-widget title opts]
   (or (bridge-op :open-simple-gui gui-widget title opts)
       (log/debug "Client bridge simple GUI not available"))))

(defn run-client-effect!
  "Run a content-owned local client effect through the installed platform host."
  ([effect-key]
   (run-client-effect! effect-key nil))
  ([effect-key payload]
   (or (bridge-op :run-client-effect effect-key payload)
       (log/debug "Client bridge effect host not available" {:effect-key effect-key}))))

;; ============================================================================
;; Minecraft client access ops — avoids Class/forName reflection in ac layer
;; ============================================================================

(defn get-client-player
  "Return the current client-side Player instance, or nil."
  []
  (or (bridge-op :get-client-player)
      (do (log/debug "Client bridge get-client-player not available")
          nil)))

(defn screen-active?
  "Return true when any Minecraft screen is currently open."
  []
  (or (bridge-op :screen-active?)
      (do (log/debug "Client bridge screen-active? not available")
          false)))

(defn close-screen!
  "Close the current Minecraft screen (set to nil). No-op if no screen is open."
  []
  (or (bridge-op :close-screen!)
      (log/debug "Client bridge close-screen! not available")))

(defn send-system-message!
  "Send a translatable system message to a player.
  Args: [player translatable-key & format-args]"
  [player translatable-key & args]
  (or (apply bridge-op :send-system-message! player translatable-key args)
      (log/debug "Client bridge send-system-message! not available")))

;; ============================================================================
;; Mouse position access — avoids net.minecraft imports in ac layer
;; ============================================================================

(defn get-mouse-pos
  "Return [x y] mouse position in the Minecraft window, or [0 0] if unavailable."
  []
  (or (bridge-op :get-mouse-pos)
      [0 0]))

(defn get-window-size
  "Return [width height] of the Minecraft window in GUI-scaled pixels.
   Throws if the bridge is not installed — callers must ensure the bridge is active."
  []
  (if-let [result (bridge-op :get-window-size)]
    result
    (throw (ex-info "platform-bridge/get-window-size: bridge not installed — cannot determine window size" {}))))

(defn game-time-ms
  "Return the current game time in milliseconds (pauses when game pauses).
   Falls back to System/currentTimeMillis if the bridge is not installed."
  []
  (or (bridge-op :game-time-ms)
      (System/currentTimeMillis)))

(defn font-width
  "Return the pixel width of a string using the Minecraft default font.
   Falls back to an approximate width (6px per char) if the bridge is not installed."
  [^String text]
  (or (bridge-op :font-width text)
      (* 6 (count text))))

(defn resolve-shader
  "Resolve a ShaderInstance by keyword name.
   Returns a ShaderInstance or nil if the shader is not loaded."
  [shader-name]
  (bridge-op :resolve-shader shader-name))

(defn draw-ops-host!
  "Create a CGUI widget inside `parent` that renders draw ops each frame.
   ops-fn is (fn [] ops-vector) called each frame.
   Returns the host widget.
   Throws if the bridge is not installed."
  [parent ops-fn]
  (if-let [f (bridge-op :draw-ops-host!)]
    (f parent ops-fn)
    (throw (ex-info "platform-bridge/draw-ops-host!: bridge not installed" {}))))

(defn register-font!
  "Register a CGUI font keyword with the given spec map.
  spec may contain :bold? and/or :italic? keys."
  [name spec]
  (or (bridge-op :register-font! name spec)
      (log/debug "Client bridge register-font! not available")))

(defn get-player-owner
  "Resolve the current client local player owner, or nil."
  []
  (or (bridge-op :get-player-owner)
      (do (log/debug "Client bridge get-player-owner not available")
          nil)))

(defn font-text-width
  "Return the pixel width of text rendered with the given font-desc and font-size.
  Falls back to an estimate if the bridge is not installed."
  [font-desc text font-size]
  (or (bridge-op :font-text-width font-desc text font-size)
      (do (log/debug "Client bridge font-text-width not available, using estimate")
          (* (count text) font-size 0.6))))

(defn stop-all-media!
  "Stop all currently playing media sounds for the given player-uuid."
  [player-uuid]
  (or (bridge-op :stop-all-media! player-uuid)
      (log/debug "Client bridge stop-all-media! not available")))

(defn has-recipes?
  "Check if any recipes exist that craft the given item-id.
   Returns true/false, or false if the bridge is not installed."
  [item-id]
  (or (bridge-op :has-recipes? item-id)
      (do (log/debug "Client bridge has-recipes? not available, returning false")
          false)))
