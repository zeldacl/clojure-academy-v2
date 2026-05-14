(ns cn.li.mcmod.gui.cgui-widget-model
  "Widget component system. 
   Decouples components from widget hierarchy to support diverse UI patterns
   (containers, overlays, etc.). Components are stateful models attached to widgets."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]))

(defn- component-kind 
  "Extract the kind/type identifier from a component map."
  [component]
  (or (:kind component) (::kind component) :unknown))

;; ============================================================================
;; Component Lifecycle
;; ============================================================================

(defn create-component-instance
  "Create a new component instance with a given kind and empty state.
   :kind - component type identifier (keyword)
   Returns component map with :kind and :state (atom)"
  [kind]
  {:kind kind :state (atom {})})

(defn add-widget-component!
  "Attach a component to a widget. Returns widget for chaining."
  [widget component]
  (swap! (:components widget) conj component)
  widget)

(defn remove-widget-component!
  "Detach a component from a widget. Returns widget for chaining."
  [widget component]
  (swap! (:components widget)
         (fn [xs] (vec (remove #(= % component) xs))))
  widget)

;; ============================================================================
;; Component Lookup
;; ============================================================================

(defn get-widget-component
  "Get the first component of a given kind attached to this widget.
   Returns nil if not found.
   :kind - component type (keyword)"
  [widget kind]
  (first (filter #(= (component-kind %) kind) @(:components widget))))

(defn get-widget-component-by-class
  "Get the first component whose kind matches a Java Class name.
   Useful for matching Java swing/component types.
   Example: (get-widget-component-by-class w net.minecraft.client.gui.GuiButton)
   Returns nil if not found."
  [widget component-class]
  (let [s (str component-class)
        ;; `str` on a Class yields strings like "class net.minecraft.foo.Bar".
        s (if (clojure.string/starts-with? s "class ") (subs s 6) s)
        simple (last (clojure.string/split s #"\."))
        kind (keyword (clojure.string/lower-case (or simple "")))]
    (get-widget-component widget kind)))

(defn get-widget-components
  "Get all components of a given kind attached to this widget."
  [widget kind]
  (filterv #(= (component-kind %) kind) @(:components widget)))

(defn get-all-widget-components
  "Get all components attached to this widget."
  [widget]
  (vec @(:components widget)))
