(ns cn.li.ac.terminal.client.apps.freq-transmitter
  "CLIENT-ONLY: interactive frequency transmitter terminal app ported from
  original AcademyCraft FreqTransmitterUI / AppFreqTransmitter.

  3-phase FSM with timeout matching original:
    :scan       — click Scan to ray-trace-select a wireless device (server-side,
                  range=4 matching original Raytrace.traceLiving)
    :configure  — view/edit device SSID (editable), password (editable+masked)
    :result     — show operation result with auto-close timeout

  Keyboard input: backspace to delete, printable chars append, Enter submits.
  Timeout: 20s per state (matching original), auto-returns to :scan on expiry."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

(def freq-scan-msg   1005)
(def freq-config-msg 1006)
(def ^:private enter-key 257)
(def ^:private backspace-key 259)
(def ^:private timeout-sec 20.0)

(defn- do-scan! [state player-uuid]
  (swap! state assoc :scanning? true :message ""
         :state-start-time (System/currentTimeMillis))
  (net-client/send-to-server freq-scan-msg
    {:player-uuid player-uuid :range 4.0}  ;; matching original range=4
    (fn [response]
      (if (:success response)
        (let [dev (:device response)]
          (swap! state assoc
                 :phase :configure :device dev
                 :ssid (:ssid dev "") :password (:password dev "")
                 :scanning? false
                 :message (str "Found " (name (:type dev)))
                 :state-start-time (System/currentTimeMillis)))
        (swap! state assoc
               :scanning? false
               :message (or (:error response) "Scan failed")
               :state-start-time (System/currentTimeMillis))))))

(defn- do-configure! [state player-uuid]
  (swap! state assoc :message "Transmitting..."
         :state-start-time (System/currentTimeMillis))
  (net-client/send-to-server freq-config-msg
    {:player-uuid player-uuid
     :ssid (:ssid @state)
     :password (:password @state)}
    (fn [response]
      (if (:success response)
        (swap! state assoc :phase :result
               :message (or (:message response) "Configuration applied!")
               :state-start-time (System/currentTimeMillis))
        (swap! state assoc :phase :result
               :message (str "Failed: " (or (:error response) "Unknown error"))
               :state-start-time (System/currentTimeMillis))))))

(defn- build-freq-gui [player]
  (let [player-uuid (uuid/player-uuid player)
        state (atom {:phase :scan
                     :device nil
                     :ssid ""
                     :password ""
                     :message ""
                     :scanning? false
                     :state-start-time (System/currentTimeMillis)
                     :editing-field nil})  ;; :ssid or :password or nil
        root (cgui-core/create-widget :size [380 300])
        enter-keys #{28 156 257}    ;; Enter, NumpadEnter, GLFW_ENTER
        backspace-key 259]           ;; GLFW_BACKSPACE

    ;; Semi-transparent background (matching original AuxGui overlay over game world)
    (let [bg (cgui-core/create-widget :pos [0 0] :size [380 300])]
      (comp/add-component! bg (comp/tint 0x77272727))
      (cgui-core/add-widget! root bg))
    ;; Glow border matching original drawGlow (ACRenderingHelper.drawGlow)
    (let [glow-h 3 glow-color-center 0xAAFFFFFF glow-color-edge 0x00FFFFFF]
      (doseq [[x y w h] [[0 0 380 glow-h]
                          [0 (- 300 glow-h) 380 glow-h]
                          [0 glow-h glow-h (- 300 (* 2 glow-h))]
                          [(- 380 glow-h) glow-h glow-h (- 300 (* 2 glow-h))]]]
        (let [border (cgui-core/create-widget :pos [x y] :size [w h])]
          (comp/add-component! border (comp/gradient-fill glow-color-center glow-color-edge))
          (cgui-core/add-widget! root border))))

    ;; Title bar with icon + name (matching original drawBox title)
    (let [tbar (cgui-core/create-widget :pos [10 8] :size [360 22])]
      (comp/add-component! tbar (comp/tint 0x77272727))
      (cgui-core/add-widget! root tbar)
      (let [title-w (cgui-core/create-widget :pos [28 3] :size [320 16])]
        (comp/add-component! title-w
          (comp/text-box :text "Frequency Transmitter" :font-size 12.0
                        :color 0xFFFFFFFF))
        (cgui-core/add-widget! root title-w)))

    ;; Content container
    (let [content (cgui-core/create-widget :pos [10 35] :size [360 255])]
      (cgui-core/set-name! content "content")
      (cgui-core/add-widget! root content))

    ;; Global keyboard handling for editable fields
    (events/on-key-press root
      (fn [evt]
        (let [{:keys [phase editing-field ssid password]} @state]
          (when (and (= phase :configure) editing-field)
            (let [key-code (:keyCode evt)
                  typed-char (:typedChar evt)]
              (cond
                (contains? enter-keys (int (or key-code 0)))
                (swap! state assoc :editing-field nil)

                (= (int (or key-code 0)) backspace-key)
                (let [field-val (if (= editing-field :ssid) ssid password)]
                  (when (pos? (count field-val))
                    (if (= editing-field :ssid)
                      (swap! state update :ssid #(subs % 0 (dec (count %))))
                      (swap! state update :password #(subs % 0 (dec (count %)))))))

                (and typed-char (not= typed-char (char 0))
                     (>= (int typed-char) 32)
                     (< (count (if (= editing-field :ssid) ssid password)) 32))
                (if (= editing-field :ssid)
                  (swap! state update :ssid str typed-char)
                  (swap! state update :password str typed-char))))))))

    ;; Per-frame UI rebuild based on phase
    (events/on-frame root
      (fn [_]
        (let [{:keys [phase device ssid password message scanning?
                     state-start-time editing-field]} @state
              cw (cgui-core/find-widget root "content")
              ;; Timeout auto-return (matching original 20s per state)
              elapsed (/ (- (System/currentTimeMillis) state-start-time) 1000.0)]
          (when (and (> elapsed timeout-sec) (not= phase :result))
            (swap! state assoc :phase :scan :device nil :message "Timed out" :editing-field nil))
          (when cw
            (cgui-core/clear-widgets! cw)
            (case phase

              :scan
              (let [hint (cgui-core/create-widget :pos [0 5] :size [360 40])]
                (comp/add-component! hint
                  (comp/text-box
                    :text "Look at a matrix or node block and click Scan.\nRange: 4 blocks (matching original)."
                    :font-size 9.0 :color 0xFFAAAAAA))
                (cgui-core/add-widget! cw hint)
                (let [btn (cgui-core/create-widget :pos [120 60] :size [120 30])]
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
                (when (and message (not scanning?))
                  (let [mw (cgui-core/create-widget :pos [0 110] :size [360 30])]
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
                ;; SSID field (editable — click to activate, backspace/Enter supported)
                (let [ssid-active? (= editing-field :ssid)
                      ssid-w (cgui-core/create-widget :pos [0 40] :size [360 24])
                      ssid-color (if ssid-active? 0xFFFFD700 0xFFFFFF88)
                      cursor (if ssid-active? "_" "")
                      ssid-text (str "SSID: " ssid cursor)]
                  (comp/add-component! ssid-w
                    (comp/text-box :text ssid-text :font-size 9.0 :color ssid-color))
                  (events/on-left-click ssid-w
                    (fn [_] (swap! state assoc :editing-field :ssid)))
                  (cgui-core/add-widget! cw ssid-w))
                ;; Password field (editable + masked)
                (let [pw-active? (= editing-field :password)
                      pw-w (cgui-core/create-widget :pos [0 72] :size [360 24])
                      pw-color (if pw-active? 0xFFFFD700 0xFFFFFF88)
                      masked (apply str (repeat (count password) "*"))
                      cursor (if pw-active? "_" "")
                      pw-text (str "Password: " masked cursor)]
                  (comp/add-component! pw-w
                    (comp/text-box :text pw-text :font-size 9.0 :color pw-color))
                  (events/on-left-click pw-w
                    (fn [_] (swap! state assoc :editing-field :password)))
                  (cgui-core/add-widget! cw pw-w))
                ;; Editing hint
                (when editing-field
                  (let [hint (cgui-core/create-widget :pos [0 100] :size [360 16])]
                    (comp/add-component! hint
                      (comp/text-box :text "Type to edit, Enter to confirm"
                                    :font-size 7.0 :color 0xFF888888))
                    (cgui-core/add-widget! cw hint)))
                ;; Apply button
                (let [btn (cgui-core/create-widget :pos [120 120] :size [120 28])]
                  (comp/add-component! btn (comp/tint 0xFF336633))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 28])]
                    (comp/add-component! lbl
                      (comp/text-box :text "Apply" :font-size 10.0
                                    :color 0xFFFFFFFF
                                    :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (events/on-left-click btn
                    (fn [_]
                      (swap! state assoc :editing-field nil)
                      (do-configure! state player-uuid)))
                  (cgui-core/add-widget! cw btn))
                ;; Rescan button
                (let [btn (cgui-core/create-widget :pos [120 156] :size [120 24])]
                  (comp/add-component! btn (comp/tint 0xFF443333))
                  (let [lbl (cgui-core/create-widget :pos [0 0] :size [120 24])]
                    (comp/add-component! lbl
                      (comp/text-box :text "Rescan" :font-size 8.0
                                    :color 0xFFFFAAAA
                                    :align :center :height-align :center))
                    (cgui-core/add-widget! btn lbl))
                  (events/on-left-click btn
                    (fn [_]
                      (swap! state assoc :phase :scan :device nil :message ""
                             :editing-field nil
                             :state-start-time (System/currentTimeMillis))))
                  (cgui-core/add-widget! cw btn)))

              :result
              (let [mw (cgui-core/create-widget :pos [0 20] :size [360 60])]
                (comp/add-component! mw
                  (comp/text-box :text message :font-size 10.0
                                :color (if (str/starts-with? (or message "") "Failed")
                                        0xFFFF8888 0xFF88FF88)))
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
                      (swap! state assoc :phase :scan :device nil :message ""
                             :editing-field nil
                             :state-start-time (System/currentTimeMillis))))
                  (cgui-core/add-widget! cw btn)))
              nil)))))

    root))

(defn open!
  "Open the interactive frequency transmitter GUI."
  [player]
  (log/info "Opening freq transmitter for" (pr-str player))
  (client-bridge/open-screen! {:cgui-root (build-freq-gui player) :title "Frequency Transmitter"}))
