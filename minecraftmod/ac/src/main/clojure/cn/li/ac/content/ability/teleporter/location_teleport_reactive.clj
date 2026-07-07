(ns cn.li.ac.content.ability.teleporter.location-teleport-reactive
  "Reactive version of location teleport screen — migration template.
   Demonstrates: XML loading + signal binding + event attachment + bridge open."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(def ^:private teleport-spec
  "Inline spec for simple list+button layout (normally loaded from XML)."
  {:kind :group :id :root :props {:w 280 :h 230 :align-w :center :align-h :middle}
   :children
   [{:kind :box :id :header
     :props {:x 0 :y 0 :w 280 :h 24 :fill 0xCC222244}}
    {:kind :text :id :title
     :props {:x 10 :y 4 :text "Location Teleport" :font-size 14 :color 0xFFFFFFFF}}
    {:kind :text :id :info
     :props {:x 10 :y 60 :text "No locations saved" :font-size 12 :color 0xFF888888}}
    {:kind :box :id :close-btn
     :props {:x 230 :y 4 :w 40 :h 18 :fill 0xFF444444 :hover-tint 0.5}}
    {:kind :text :id :close-txt
     :props {:x 0 :y 0 :text "Close" :font-size 12 :color 0xFFFF4444}}]})

(defn create-runtime []
  (let [r (rt/create-runtime)]
    (rt/build! r teleport-spec)
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Location Teleport")))
