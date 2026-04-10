(ns cn.li.ac.terminal.apps.freq-transmitter
  "Frequency Transmitter app - Manage wireless frequencies."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn- create-freq-transmitter-gui
  "Create the frequency transmitter GUI."
  [_player]
  (let [root (cgui/create-widget :size [450 400])

        ;; Background
        bg (cgui/create-widget :pos [0 0] :size [450 400])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)

        ;; Title
        title (cgui/create-widget :pos [0 20] :size [450 30])
        title-text (comp/text-box :text "Frequency Transmitter" :color 0xFFFFFFFF :scale 1.5)
        _ (comp/add-component! title title-text)

        ;; Content
        content-y 70
        content-lines ["Wireless Network Management"
                      ""
                      "What is a Frequency?"
                      "Frequencies allow wireless devices to communicate"
                      "on separate channels. Devices on the same frequency"
                      "can share energy and data."
                      ""
                      "How to Use:"
                      "1. Craft a Frequency Transmitter item"
                      "2. Right-click to set a frequency"
                      "3. Use on wireless nodes to assign frequency"
                      "4. All nodes with same frequency connect"
                      ""
                      "Network Tips:"
                      "- Use different frequencies for different systems"
                      "- Matrix cores manage network connections"
                      "- Nodes automatically find nearby matrices"
                      "- Energy flows through connected networks"
                      ""
                      "Advanced Features:"
                      "- Create private networks with unique frequencies"
                      "- Share frequencies with team members"
                      "- Monitor network energy flow"
                      "- Optimize node placement for coverage"]

        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui/create-widget :pos [30 (+ content-y (* idx 13))] :size [390 13])
                                 t (comp/text-box :text line :color 0xFFFFFFFF :scale 0.7)]
                             (comp/add-component! w t)
                             w))
                         content-lines)]

    (cgui/add-widget! root bg)
    (cgui/add-widget! root title)
    (doseq [w content-widgets]
      (cgui/add-widget! root w))

    root))

(defn open-freq-transmitter-gui
  "Open the frequency transmitter GUI for a player."
  [player]
  (log/info "Opening frequency transmitter for player:" player)
  (let [gui (create-freq-transmitter-gui player)]
    (client-bridge/open-simple-gui! gui "Frequency Transmitter")))

;; ============================================================================
;; App Registration
;; ============================================================================

(def freq-transmitter-app
  {:id :freq-transmitter
   :name "Frequency Transmitter"
   :icon "academy:textures/guis/apps/freq_transmitter/icon.png"
   :description "Manage wireless frequencies"
   :gui-fn 'cn.li.ac.terminal.apps.freq-transmitter/open-freq-transmitter-gui
   :category :wireless})

(defonce ^:private freq-transmitter-installed? (atom false))

(defn init-freq-transmitter-app!
  []
  (when (compare-and-set! freq-transmitter-installed? false true)
    (reg/register-app! freq-transmitter-app)))
