(ns cn.li.presentation.core.components
  "Pure, content-neutral component constructors shared by AC/BC/CC views.")

(defn panel
  ([key children] (panel key {} children))
  ([key layout children] {:type :stack :key key :layout layout :children (vec children)}))

(defn nine-slice [key resource layout]
  {:type :nine-slice :key key :layout layout :style {:resource resource}})

(defn label [key text-bind layout]
  {:type :text :key key :layout layout :bind {:text text-bind}})

(defn button [key text-bind action layout]
  {:type :button :key key :layout layout :bind {:text text-bind}
   :on {:activate action} :semantics {:role :button}})

(defn text-field [key text-bind field change-action submit-action layout]
  {:type :text-input :key key :layout layout :bind {:text text-bind}
   :on {:change change-action :submit submit-action}
   :semantics {:role :textbox :field field}})

(defn property-row [key label-bind value-bind layout]
  {:type :row :key key :layout layout
   :children [(label (keyword (str (name key) "/label")) label-bind {})
              (label (keyword (str (name key) "/value")) value-bind {})]})

(defn histogram [key value-bind layout]
  {:type :progress :key key :layout layout :bind {:value value-bind}
   :semantics {:role :progress}})

(defn slot-anchor [key index layout]
  {:type :slot-anchor :key key :layout layout
   :bind {:value [:state :slots] :index index}
   :semantics {:role :slot}})

(defn hud-anchor [key stage layout children]
  {:type :absolute :key key :layout (assoc layout :stage stage)
   :children (vec children)})
