(ns my-mod.wireless.gui.solar-gui-xml
  "Solar Generator GUI screen (TechUI/CGUI).

  Uses existing XML page: assets/my_mod/guis/rework/page_solar.xml
  and composes it into TechUI tabs (inv + wireless) with InfoArea."
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.platform-adapter :as gui]
            [my-mod.gui.tabbed-gui :as tabbed-gui]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.wireless.gui.wireless-tab :as wireless-tab]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log]))

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

(defn- status->frame-v0
  "Map Solar status string to v0 for 3-frame vertical texture."
  [status]
  (case status
    "STOPPED" (/ 1.0 3.0)
    "STRONG" 0.0
    "WEAK" (/ 2.0 3.0)
    (/ 1.0 3.0)))

(def ^:private effect-solar-texture
  (modid/asset-path "textures" "guis/effect/effect_solar.png"))

(defn- attach-anim-frame!
  "Attach per-frame UV update to `ui_block/anim_frame` so runtime renders 3-frame effect texture.
   Degrades gracefully if widget is missing."
  [inv-window container]
  (when-let [anim-frame (cgui/find-widget inv-window "ui_block/anim_frame")]
    (events/on-frame anim-frame
      (fn [_]
        (try
          (let [v0 (status->frame-v0 @(:status container))
                v1 (+ v0 (/ 1.0 3.0))]
            (comp/render-texture-region anim-frame effect-solar-texture 0 0 0 0 0.0 v0 1.0 v1))
          (catch Exception _e
            ;; Keep UI alive even if texture path/resource fails at runtime.
            nil))))))

(declare create-wireless-panel)

(defn create-solar-gui
  "Create Solar Generator GUI root widget."
  [container _player & [opts]]
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_solar.xml"))
          inv-window (cgui-doc/get-widget doc "main")
          wireless-window (wireless-tab/create-wireless-panel {:mode :generator :container container})
          inv-page {:id "inv" :window inv-window}
          wireless-page {:id "wireless" :window wireless-window}
          pages [inv-page wireless-page]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          main-widget (:window tech-ui)
          info-area (tech-ui/create-info-area)
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          speed-str (fn []
                      (try
                        (format "%.2fIF/T" (double @(:gen-speed container)))
                        (catch Exception _ "0.00IF/T")))]

      (attach-anim-frame! inv-window container)

      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (tech-ui/reset-info-area! info-area)
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-buffer (fn [] (double @(:energy container)))
                                      (max-e))]
                0)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "gen_speed" speed-str y)
            y (tech-ui/add-property info-area "status" (fn [] @(:status container)) y)]
        y)

      (cgui/add-widget! main-widget info-area)

      (log/info "Created Solar Generator GUI (TechUI)")
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Solar GUI:" (.getMessage e))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Solar GUI."
  [container minecraft-container player]
  (let [gui (create-solar-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
      (tech-ui/assoc-tech-ui-screen-size base))))

