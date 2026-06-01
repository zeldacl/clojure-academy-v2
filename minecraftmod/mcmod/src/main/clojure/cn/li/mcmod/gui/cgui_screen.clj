(ns cn.li.mcmod.gui.cgui-screen
  "CGUI screen and focus management.
   Screens are top-level UI containers with focus tracking and event routing.
   Focus management emits gain-focus/lost-focus events for widget lifecycle."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as components]
            [cn.li.mcmod.gui.events :as events]))

;; ============================================================================
;; CGUI Creation & Root Management
;; ============================================================================

(defn create-cgui
  "Create a new CGUI instance with focus tracking and drag state.
   Returns a cgui map with :type and :root (root widget)."
  []
  (let [root (cgui-core/create-container :name "root" :pos [0 0] :size [0 0])]
    ;; per-cgui runtime state stored in root metadata for platform runtime access
    (swap! (:metadata root) assoc :cgui-focus (atom nil)
                                  :dragging-node (atom nil)
                                  :last-drag-time (atom 0)
                                  :last-start-time (atom 0))
    {:type :cgui :root root}))

(defn get-root 
  "Get the root widget of a CGUI instance."
  [cgui]
  (:root cgui))

(defn cgui-add-widget!
  "Add a widget to the root of a CGUI. Returns cgui for chaining."
  [cgui widget]
  (cgui-core/add-widget! (get-root cgui) widget)
  cgui)

;; ============================================================================
;; Focus Management
;; ============================================================================

(defn get-focus
  "Get the currently focused widget for this CGUI root.
   Returns nil if nothing is focused."
  [root]
  (when root
    (let [m @(:metadata root)]
      (when-let [a (:cgui-focus m)]
        @a))))

(defn gain-focus!
  "Set focus to a widget for this CGUI root. Emits :gain-focus / :lost-focus events.
   :root - the CGUI root widget (the value returned by get-root)
   :widget - the widget to focus (or nil to clear)
   Returns the focused widget."
  [root widget]
  (when root
    (let [m @(:metadata root)
          a (:cgui-focus m)]
      (when a
        (let [old @a]
          (when (and old (not= old widget))
            (swap! (:metadata old) assoc :focused? false)
            (events/emit-widget-event! old :lost-focus {:new-focus widget}))
          (reset! a widget)
          (when widget
            (swap! (:metadata widget) assoc :focused? true)
            (events/emit-widget-event! widget :gain-focus {:old-focus old}))
          widget)))))

(defn remove-focus!
  "Clear focus for this CGUI root. Emits :lost-focus event.
   Returns nil."
  [root]
  (when root
    (let [m @(:metadata root)
          a (:cgui-focus m)
          old (when a @a)]
      (when a
        (reset! a nil))
      (when old
        (swap! (:metadata old) assoc :focused? false)
        (events/emit-widget-event! old :lost-focus {:new-focus nil}))
      nil)))

;; ============================================================================
;; Screen Construction
;; ============================================================================

(defn create-cgui-screen
  "Create a CGUI screen wrapper (for display/rendering on client).
   Returns screen map with :type and :cgui."
  [cgui]
  {:type :cgui-screen :cgui cgui})

(defn create-cgui-screen-container
  "Create a CGUI screen wrapper backed by a Minecraft Container.
   This bridges CGUI to server-side inventory sync logic.
   :cgui - CGUI instance
   :container - Minecraft Container instance
   Returns screen-container map with :type, :cgui, and :minecraft-container."
  [cgui container]
  {:type :cgui-screen-container :cgui cgui :minecraft-container container})

;; ============================================================================
;; Widget Tree Building
;; ============================================================================

(defn build-widget-tree
  "Recursively build a widget tree from a spec map.
   Spec format: {:type :widget|:container, :name str, :pos [x y], :size [w h],
                 :scale num, :z-level num, :components [comp...], :children [spec...]}
   Returns the root widget of the built tree."
  [spec]
  (let [{:keys [type name pos size scale z-level components children]
         :or {type :widget name nil pos [0 0] size [0 0] scale 1.0 z-level 0}} spec
        widget-fn (if (= type :container) cgui-core/create-container cgui-core/create-widget)
        widget (widget-fn :name name :pos pos :size size :scale scale :z-level z-level)]
    (doseq [component components]
      (components/add-widget-component! widget component))
    (doseq [child-spec children]
      (cgui-core/add-widget! widget (build-widget-tree child-spec)))
    widget))

;; ============================================================================
;; Utilities
;; ============================================================================

(defn progressbar-direction-enum
  "Utility: Return progressbar direction enum value (passthrough for now)."
  [direction]
  direction)
