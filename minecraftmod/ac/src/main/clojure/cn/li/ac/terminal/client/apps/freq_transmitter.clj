(ns cn.li.ac.terminal.client.apps.freq-transmitter
  "CLIENT-ONLY: interactive frequency transmitter terminal app ported from
  original AcademyCraft FreqTransmitterUI / AppFreqTransmitter.

  3-phase FSM:
    :scan       — click Scan to ray-trace-select a wireless device (server-side)
    :configure  — view/edit device SSID, password
    :result     — show operation result

  Server-side handlers are in cn.li.ac.terminal.freq-network."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(def freq-scan-msg   1005)
(def freq-config-msg 1006)

(defn- do-scan! [state player-uuid]
  (swap! state assoc :scanning? true :message "")
  (net-client/send-to-server freq-scan-msg
    {:player-uuid player-uuid}
    (fn [response]
      (if (:success response)
        (let [dev (:device response)]
          (swap! state assoc
                 :phase :configure :device dev
                 :ssid (:ssid dev "") :password (:password dev "")
                 :scanning? false
                 :message (str "Found " (name (:type dev)))))
        (swap! state assoc
               :scanning? false
               :message (or (:error response) "Scan failed"))))))

(defn- do-configure! [state player-uuid]
  (swap! state assoc :message "Applying...")
  (net-client/send-to-server freq-config-msg
    {:player-uuid player-uuid
     :ssid (:ssid @state)
     :password (:password @state)}
    (fn [response]
      (if (:success response)
        (swap! state assoc :phase :result
               :message (or (:message response) "Configuration applied!"))
        (swap! state assoc :phase :result
               :message (str "Failed: " (or (:error response) "Unknown error")))))))

(defn- build-freq-gui [player]
  (let [player-uuid (uuid/player-uuid player)
        state (atom {:phase :scan
                     :device nil
                     :ssid ""
                     :password ""
                     :message ""
                     :scanning? false})
        root (cgui-core/create-widget :size [380 300])]

    ;; Background
    (let [bg (cgui-core/create-widget :pos [0 0] :size [380 300])]
      (comp/add-component! bg (comp/tint 0xC0272727))
      (cgui-core/add-widget! root bg))

    ;; Title bar
    (let [tbar (cgui-core/create-widget :pos [10 8] :size [360 20])]
      (comp/add-component! tbar
        (comp/text-box :text "Frequency Transmitter" :font-size 12.0
                      :color 0xFFFFFFFF))
      (cgui-core/add-widget! root tbar))

    ;; Content container
    (let [content (cgui-core/create-widget :pos [10 35] :size [360 255])]
      (cgui-core/set-name! content "content")
      (cgui-core/add-widget! root content))

    ;; Per-frame UI rebuild based on phase
    (events/on-frame root
      (fn [_]
        (let [{:keys [phase device ssid password message scanning?]} @state
              cw (cgui-core/find-widget root "content")]
          (when cw
            (cgui-core/clear-widgets! cw)
            (case phase

              :scan
              (let [hint (cgui-core/create-widget :pos [0 5] :size [360 60])]
                (comp/add-component! hint
                  (comp/text-box
                    :text "Look at a matrix or node block and click Scan.\nThe server will ray-trace your view direction."
                    :font-size 9.0 :color 0xFFAAAAAA))
                (cgui-core/add-widget! cw hint)
                ;; Scan button
                (let [btn (cgui-core/create-widget :pos [120 80] :size [120 30])]
                  (comp/add-component! btn
                    (comp/tint (if scanning? 0xFF555555 0xFF334455)))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 30])]
                    (comp/add-component! lbl
                      (comp/text-box
                        :text (if scanning? "Scanning..." "Scan Target")
                        :font-size 10.0 :color 0xFFFFFFFF
                        :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (when-not scanning?
                    (events/on-left-click btn
                      (fn [_] (do-scan! state player-uuid))))
                  (cgui-core/add-widget! cw btn))
                ;; Error message
                (when (and message (not scanning?))
                  (let [mw (cgui-core/create-widget :pos [0 125] :size [360 30])]
                    (comp/add-component! mw
                      (comp/text-box :text message :font-size 8.0
                                    :color 0xFFFFAAAA))
                    (cgui-core/add-widget! cw mw))))

              :configure
              (when device
                ;; Device info header
                (let [info-str (str (name (:type device))
                                   (when (:ssid device) (str " [" (:ssid device) "]"))
                                   (when (contains? device :has-network)
                                     (if (:has-network device) " (active)" " (no network)"))
                                   (when (contains? device :linked?)
                                     (if (:linked? device) " (linked)" " (unlinked)")))]
                  (let [iw (cgui-core/create-widget :pos [0 5] :size [360 24])]
                    (comp/add-component! iw
                      (comp/text-box :text info-str :font-size 9.0
                                    :color 0xFFAADDFF))
                    (cgui-core/add-widget! cw iw)))
                ;; SSID field
                (let [iw (cgui-core/create-widget :pos [0 40] :size [360 24])]
                  (comp/add-component! iw
                    (comp/text-box :text (str "SSID: " ssid) :font-size 9.0
                                  :color 0xFFFFFF88))
                  (cgui-core/add-widget! cw iw))
                ;; Password field
                (let [iw (cgui-core/create-widget :pos [0 72] :size [360 24])]
                  (comp/add-component! iw
                    (comp/text-box :text (str "Password: " password) :font-size 9.0
                                  :color 0xFFFFFF88))
                  (cgui-core/add-widget! cw iw))
                ;; Apply button
                (let [btn (cgui-core/create-widget :pos [120 110] :size [120 28])]
                  (comp/add-component! btn (comp/tint 0xFF336633))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 28])]
                    (comp/add-component! lbl
                      (comp/text-box :text "Apply" :font-size 10.0
                                    :color 0xFFFFFFFF
                                    :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (events/on-left-click btn
                    (fn [_] (do-configure! state player-uuid)))
                  (cgui-core/add-widget! cw btn))
                ;; Rescan button
                (let [btn (cgui-core/create-widget :pos [120 148] :size [120 24])]
                  (comp/add-component! btn (comp/tint 0xFF443333))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 24])]
                    (comp/add-component! lbl
                      (comp/text-box :text "Rescan" :font-size 8.0
                                    :color 0xFFFFAAAA
                                    :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (events/on-left-click btn
                    (fn [_]
                      (swap! state assoc :phase :scan :device nil :message "")))
                  (cgui-core/add-widget! cw btn)))

              :result
              (let [mw (cgui-core/create-widget :pos [0 20] :size [360 60])]
                (comp/add-component! mw
                  (comp/text-box :text message :font-size 10.0
                                :color 0xFF88FF88))
                (cgui-core/add-widget! cw mw)
                (let [btn (cgui-core/create-widget :pos [120 100] :size [120 28])]
                  (comp/add-component! btn (comp/tint 0xFF334455))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 28])]
                    (comp/add-component! lbl
                      (comp/text-box :text "Done" :font-size 10.0
                                    :color 0xFFFFFFFF
                                    :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (events/on-left-click btn
                    (fn [_]
                      (swap! state assoc :phase :scan :device nil :message "")))
                  (cgui-core/add-widget! cw btn)))
              nil)))))

    root))

(defn open!
  "Open the interactive frequency transmitter GUI."
  [player]
  (log/info "Opening freq transmitter for" (pr-str player))
  (client-bridge/open-simple-gui! (build-freq-gui player) "Frequency Transmitter"))
