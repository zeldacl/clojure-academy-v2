(ns cn.li.presentation.core.primitive
  "Single source of truth for the PUI primitive vocabulary.

   The catalog is neutral data only.  The compiler uses `types` for source
   validation; the runtime uses `blueprints` to instantiate primitive nodes
   during an ephemeral Composition edit.  It is never serialized wholesale
   into a view Artifact.")

(def container-types
  #{"absolute" "row" "column" "grid" "stack" "clip" "scroll"
    "portal" "repeater" "conditional" "switch" "transform" "mask"})

(def types
  #{"absolute" "row" "column" "grid" "stack" "clip" "scroll" "portal"
    "repeater" "conditional" "switch" "transform" "mask" "rect" "image"
    "nine-slice" "text" "line" "gradient" "progress" "radial-progress"
    "glow-line" "button" "text-input" "item-preview" "model-preview"
    "slot-anchor" "composite"})

(defn- props-schema [type]
  (case type
    "button" {:action :action-id :text :binding}
    "text-input" {:text :binding}
    "progress" {:value :binding}
    {}))

(def blueprints
  "Runtime-only primitive construction descriptors keyed by canonical name."
  (into {}
        (map (fn [type]
               [type {:id type
                      :edit-policy (if (contains? container-types type)
                                     :children
                                     :sealed)
                      :props-schema (props-schema type)
                      :slot-schema {}
                      :template {:key "primitive-template"
                                 :blueprint type
                                 :type (keyword type)
                                 :children []}}])
             types)))
