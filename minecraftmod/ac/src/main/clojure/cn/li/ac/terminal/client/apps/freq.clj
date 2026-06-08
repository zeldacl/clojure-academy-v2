(ns cn.li.ac.terminal.client.apps.freq
  "CLIENT-ONLY: frequency transmitter static help page."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.util.log :as log]))

(defn- create-gui
  [_player]
  (let [root (cgui-core/create-widget :size [450 400])
        bg (cgui-core/create-widget :pos [0 0] :size [450 400])
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title (cgui-core/create-widget :pos [0 20] :size [450 30])
        _ (comp/add-component! title (comp/text-box :text "Frequency Transmitter" :font :ac-normal :font-size 14 :color 0xFFFFFFFF))
        lines ["Frequency Transmitter"
               ""
               "Use this app as a reference for wireless linking."
               "Matrix: right-click a matrix and enter its password to link a node."
               "Node: right-click a node and enter its password to link generators/receivers."
               ""
               "Interactive terminal flow for wireless commands is not wired yet."]
        content-y 70
        widgets (map-indexed
                 (fn [idx line]
                   (let [w (cgui-core/create-widget :pos [30 (+ content-y (* idx 13))] :size [390 13])]
                     (comp/add-component! w (comp/text-box :text line :font :ac-normal :font-size 8 :color 0xFFFFFFFF))
                     w))
                 lines)]
    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title)
    (doseq [w widgets]
      (cgui-core/add-widget! root w))
    root))

(defn open!
  [player]
  (log/info "Opening frequency transmitter for player:" player)
  (client-bridge/open-simple-gui! (create-gui player) "Frequency Transmitter"))
