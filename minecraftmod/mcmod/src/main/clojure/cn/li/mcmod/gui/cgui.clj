(ns cn.li.mcmod.gui.cgui
  "Pure Clojure CGui model and operations.
   
   REFACTORED FOR CLARITY (Phase A.2):
   This namespace has been reorganized into logical sub-modules while maintaining full backward compatibility.
   
   Sub-modules:
   - cgui-core: Widget hierarchy and properties
   - cgui-widget-model: Component system
   - cgui-events: Event handling
   - cgui-screen: CGUI screens and focus management
   
   This wrapper re-exports all public APIs for backward compatibility.
   Direct dependencies on this namespace will continue to work without modification."
  (:require [cn.li.mcmod.gui.cgui-core :as core]
            [cn.li.mcmod.gui.cgui-widget-model :as model]
            [cn.li.mcmod.gui.cgui-events :as events]
            [cn.li.mcmod.gui.cgui-screen :as screen]))

;; ============================================================================
;; cgui-core: Widget Creation & Hierarchy (170 LOC)
;; ============================================================================

(def create-widget 
  "Create a basic widget with optional properties.
   :name - widget identifier (default nil)
   :pos - [x y] position (default [0 0])
   :size - [w h] size (default [0 0])
   :scale - scale factor (default 1.0)
   :z-level - rendering depth (default 0)"
  core/create-widget)

(def create-container 
  "Create a container widget (can hold child widgets)."
  core/create-container)

(def copy-widget 
  "Deep copy a widget with all its properties and children."
  core/copy-widget)

(def add-widget! 
  "Add a child widget to a container. Returns the container for chaining."
  core/add-widget!)

(def remove-widget! 
  "Remove a child widget from its parent container by identity."
  core/remove-widget!)

(def clear-widgets! 
  "Remove all child widgets from a container."
  core/clear-widgets!)

(def get-widgets 
  "Get all child widgets from a container as a vector."
  core/get-widgets)

(def get-draw-list 
  "Get rendering order of child widgets (currently same as child order)."
  core/get-draw-list)

(def find-widget 
  "Find widget by path string (e.g., \"root/panel/button\") or name. Returns nil if not found."
  core/find-widget)

(def set-name! 
  "Set the widget's name. Returns widget for chaining."
  core/set-name!)

(def get-name 
  "Get the widget's name (empty string if nil)."
  core/get-name)

(def set-pos! 
  "Set the widget's position [x y]. Returns widget for chaining."
  core/set-pos!)

(def set-position! 
  "Alias for set-pos!. Returns widget for chaining."
  core/set-position!)

(def get-pos 
  "Get the widget's position as [x y] vector."
  core/get-pos)

(def set-size! 
  "Set the widget's size [w h]. Returns widget for chaining."
  core/set-size!)

(def get-size 
  "Get the widget's size as [w h] vector."
  core/get-size)

(def get-width 
  "Get the widget's width as a double."
  core/get-width)

(def get-height 
  "Get the widget's height as a double."
  core/get-height)

(def set-scale! 
  "Set the widget's scale factor. Returns widget for chaining."
  core/set-scale!)

(def set-w-align! 
  "Set horizontal alignment metadata on widget.
   Accepts keywords or strings: :left/:center/:right or \"left\"/\"center\"/\"right\".
   Returns widget for chaining."
  core/set-w-align!)

(def set-h-align! 
  "Set vertical alignment metadata on widget.
   Accepts keywords or strings: :top/:middle/:bottom or \"top\"/\"middle\"/\"bottom\".
   Returns widget for chaining."
  core/set-h-align!)

(def set-z-level! 
  "Set the widget's rendering depth. Returns widget for chaining."
  core/set-z-level!)

(def set-visible! 
  "Set widget visibility. Returns widget for chaining."
  core/set-visible!)

(def visible? 
  "Check if widget is visible."
  core/visible?)

(def widget->map 
  "Convert widget to a plain map representation (recursive for children).
   Useful for serialization or debugging."
  core/widget->map)

;; ============================================================================
;; cgui-widget-model: Components (60 LOC)
;; ============================================================================

(def create-component-instance 
  "Create a new component instance with a given kind and empty state.
   Returns component map with :kind and :state (atom)."
  model/create-component-instance)

(def add-widget-component! 
  "Attach a component to a widget. Returns widget for chaining."
  model/add-widget-component!)

(def remove-widget-component! 
  "Detach a component from a widget. Returns widget for chaining."
  model/remove-widget-component!)

(def get-widget-component 
  "Get the first component of a given kind attached to this widget."
  model/get-widget-component)

(def get-widget-component-by-class 
  "Get the first component whose kind matches a Java Class name."
  model/get-widget-component-by-class)

(def get-widget-components 
  "Get all components of a given kind attached to this widget."
  model/get-widget-components)

(def get-all-widget-components 
  "Get all components attached to this widget."
  model/get-all-widget-components)

;; ============================================================================
;; cgui-events: Event Handling (50 LOC)
;; ============================================================================

(def listen-widget-event! 
  "Register an event handler on a widget.
   Multiple handlers can be registered for the same event (all will be called).
   Returns widget for chaining."
  events/listen-widget-event!)

(def unlisten-widget-event! 
  "Unregister all handlers for an event type on this widget. Returns widget for chaining."
  events/unlisten-widget-event!)

(def clear-widget-events! 
  "Remove all event handlers from a widget. Returns widget for chaining."
  events/clear-widget-events!)

(def emit-widget-event! 
  "Fire an event on a widget. All registered handlers for that event will be called.
   Returns the event object (possibly modified by stop-event-propagation!)."
  events/emit-widget-event!)

(def stop-event-propagation! 
  "Mark an event as handled/canceled to stop further processing."
  events/stop-event-propagation!)

(def event-canceled? 
  "Check if an event has been marked as canceled."
  events/event-canceled?)

(def get-widget-event-handlers 
  "Get all handlers registered for a specific event type on this widget."
  events/get-widget-event-handlers)

;; ============================================================================
;; cgui-screen: Screens & Focus (80 LOC)
;; ============================================================================

(def create-cgui 
  "Create a new CGUI instance with focus tracking and drag state.
   Returns a cgui map with :type and :root (root widget)."
  screen/create-cgui)

(def get-root 
  "Get the root widget of a CGUI instance."
  screen/get-root)

(def cgui-add-widget! 
  "Add a widget to the root of a CGUI. Returns cgui for chaining."
  screen/cgui-add-widget!)

(def get-focus 
  "Get the currently focused widget for this CGUI root.
   Returns nil if nothing is focused."
  screen/get-focus)

(def gain-focus! 
  "Set focus to a widget for this CGUI root. Emits :gain-focus / :lost-focus events.
   Returns the focused widget."
  screen/gain-focus!)

(def remove-focus! 
  "Clear focus for this CGUI root. Emits :lost-focus event. Returns nil."
  screen/remove-focus!)

(def create-cgui-screen 
  "Create a CGUI screen wrapper (for display/rendering on client).
   Returns screen map with :type and :cgui."
  screen/create-cgui-screen)

(def create-cgui-screen-container 
  "Create a CGUI screen wrapper backed by a Minecraft Container.
   This bridges CGUI to server-side inventory sync logic."
  screen/create-cgui-screen-container)

(def build-widget-tree 
  "Recursively build a widget tree from a spec map.
   Spec format: {:type :widget|:container, :name str, :pos [x y], :size [w h],
                 :scale num, :z-level num, :components [comp...], :children [spec...]}"
  screen/build-widget-tree)

(def progressbar-direction-enum 
  "Utility: Return progressbar direction enum value (passthrough for now)."
  screen/progressbar-direction-enum)

