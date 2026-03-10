(ns my-mod.wireless.gui.solar-gui-xml
  "Solar Generator GUI screen (TechUI/CGUI).

  Uses existing XML page: assets/my_mod/guis/rework/page_solar.xml
  and overlays a small info panel for status + energy."
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log]))

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

(defn- status-color
  [status]
  (case status
    "STRONG" 0xFF25c4ff
    "WEAK" 0xFFff6c00
    0xFFAAAAAA))

(defn create-solar-gui
  "Create Solar Generator GUI root widget."
  [container _player]
  (let [inv-page (tech-ui/create-inventory-page "windbase") ; page_solar.xml currently uses ui_windbase theme
        main-widget (cgui/create-container :pos [-18 0] :size [gui-width gui-height])
        page (try
               (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_solar.xml"))]
                 (cgui-doc/get-widget doc "main"))
               (catch Exception e
                 (log/error "Failed to load page_solar.xml:" (.getMessage e))
                 nil))
        info-area (tech-ui/create-info-area)]

    ;; Base window (inventory page is guaranteed to exist)
    (cgui/add-widget! main-widget (:window inv-page))

    ;; Add solar XML page content behind (if present)
    (when page
      (cgui/add-widget! (:window inv-page) page))

    ;; Build info area (dynamic)
    (cgui/set-position! info-area (+ (cgui/get-width main-widget) 7) 5)
    (let [energy-elem (tech-ui/hist-energy (fn [] @(:energy container))
                                          (max 1.0 @(:max-energy container)))]
      (cgui/clear-widgets! info-area)
      (tech-ui/add-histogram info-area [energy-elem] 10)
      (let [y (tech-ui/add-sepline info-area "Solar" 102)]
        (tech-ui/add-property info-area "Status"
                              (fn [] @(:status container))
                              y)))

    ;; Status widget (colored)
    (let [status-widget (cgui/create-widget :pos [6 6] :size [88 10])
          label (comp/text-box :text "Status" :color 0xFFAAAAAA :scale 0.8)
          value (comp/text-box :text "" :color 0xFFFFFFFF :scale 0.8)]
      (comp/add-component! status-widget label)
      (comp/add-component! status-widget value)
      (events/on-frame status-widget
        (fn [_]
          (let [s @(:status container)]
            (comp/set-text! value s)
            (comp/set-text-color! value (status-color s)))))
      (cgui/add-widget! (:window inv-page) status-widget))

    (cgui/add-widget! main-widget info-area)
    main-widget))

(defn create-screen
  "Create CGuiScreenContainer for Solar GUI."
  [container minecraft-container player]
  (let [root (create-solar-gui container player)]
    (cgui/create-cgui-screen-container root minecraft-container)))

