(ns cn.li.ac.terminal.apps.freq-transmitter
  "Frequency Transmitter app - Manage wireless frequencies."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.terminal.apps.freq-transmitter-state :as ft-state]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn- create-freq-transmitter-gui
  "Create the frequency transmitter GUI."
  [_player]
  (let [root (cgui-core/create-widget :size [450 400])
        state (ft-state/initial-state {:now-ms 0})

        ;; Background
        bg (cgui-core/create-widget :pos [0 0] :size [450 400])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)

        ;; Title
        title (cgui-core/create-widget :pos [0 20] :size [450 30])
        title-text (comp/text-box :text "Frequency Transmitter" :color 0xFFFFFFFF :scale 1.5)
        _ (comp/add-component! title title-text)

        ;; Content
        content-y 70
        content-lines (concat
                        (ft-state/intro-lines state)
                        ["" "Current status: intro/help"
                         "Server commands ready:"
                         "- query-ssid"
                         "- auth-matrix / auth-node"
                         "- link-node / link-user"
                         ""
                         "Interactive CGui flow is backed by a pure"
                         "state machine and server-side revalidation."])

        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui-core/create-widget :pos [30 (+ content-y (* idx 13))] :size [390 13])
                                 t (comp/text-box :text line :color 0xFFFFFFFF :scale 0.7)]
                             (comp/add-component! w t)
                             w))
                         content-lines)]

    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title)
    (doseq [w content-widgets]
      (cgui-core/add-widget! root w))

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
  :icon "my_mod:textures/guis/apps/freq_transmitter/icon.png"
   :description "Manage wireless frequencies"
   :gui-fn 'cn.li.ac.terminal.apps.freq-transmitter/open-freq-transmitter-gui
   :category :wireless})

(defonce-guard freq-transmitter-installed?)

(defn init-freq-transmitter-app!
  []
  (with-init-guard freq-transmitter-installed?
    (reg/register-app! freq-transmitter-app)))
