(ns cn.li.ac.terminal.client.apps.settings
  "CLIENT-ONLY: interactive settings app ported from original AcademyCraft
  SettingsUI.  Loads settings.xml layout, populates checkbox rows from config
  descriptors, and persists toggles via Forge ConfigValue.set() + ModConfig.save().

  Uses requiring-resolve for forge1201.config.bridge because forge-1.20.1 is
  not a direct compile-time dependency of the ac module."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as xml-parser]
            [cn.li.mcmod.util.log :as log]))

;; --- Texture paths ---

(def ^:private check-tex-true
  (modid/asset-path "textures/guis" "check_true.png"))

(def ^:private check-tex-false
  (modid/asset-path "textures/guis" "check_false.png"))

;; --- Property definitions ---
;; Each prop: {:key keyword :category string :get (fn [] bool)
;;             :domain keyword :sp-only? boolean}

(def ^:private props
  [{:key :attack-player
    :category "generic"
    :get ability-config/attack-player-enabled?
    :domain config-common/ability-domain
    :sp-only? true}
   {:key :destroy-blocks
    :category "generic"
    :get ability-config/destroy-blocks-enabled?
    :domain config-common/ability-domain
    :sp-only? true}
   {:key :heads-or-tails
    :category "generic"
    :get #(boolean (config-reg/get-config-value config-common/gameplay-domain :heads-or-tails false))
    :domain config-common/gameplay-domain
    :sp-only? false}
   {:key :use-mouse-wheel
    :category "generic"
    :get gameplay-config/use-mouse-wheel-enabled?
    :domain config-common/gameplay-domain
    :sp-only? false}])

;; --- Checkbox row builder ---

(defn- set-forge-config-value!
  "Persist a config value via the Forge bridge (dynamic resolution because
  forge-1.20.1 is not a compile-time dependency of ac module)."
  [domain key value]
  (try
    (when-let [set-fn (requiring-resolve 'cn.li.forge1201.config.bridge/set-config-value!)]
      (set-fn domain key value))
    (catch Throwable _ nil)))

(defn- build-checkbox-row
  "Copy the t_checkbox template from the XML doc, set label text, wire
  click-to-toggle with Forge persistence."
  [doc {:keys [key get domain]}]
  (let [template (xml-parser/get-widget doc "t_checkbox")
        row  (cgui-core/copy-widget template)
        text-w (cgui-core/find-widget row "text")
        box-w  (cgui-core/find-widget row "box")]
    ;; Set label — matches original I18n.format("ac.settings.prop." + id)
    (when text-w
      (comp/set-text! (comp/get-textbox-component text-w) (name key)))
    ;; Set initial checkbox texture
    (let [update-tex! (fn [checked?]
                        (when box-w
                          (let [tex (if checked? check-tex-true check-tex-false)]
                            (comp/set-texture! (comp/get-drawtexture-component box-w) tex))))]
      (update-tex! (get))
      ;; Click to toggle
      (when box-w
        (events/on-left-click box-w
          (fn [_]
            (let [new-val (not (get))]
              (set-forge-config-value! domain key new-val)
              (update-tex! new-val))))))
    row))

;; --- Main UI builder ---

(defn- build-settings-gui []
  (let [xml-path (modid/asset-path "guis" "settings.xml")
        doc  (xml-parser/read-xml xml-path)
        root (cgui-core/copy-widget (xml-parser/get-widget doc "main"))
        area (cgui-core/find-widget root "area")
        ;; Content widget that holds ElementList children
        list-w (cgui-core/create-widget :pos [0 0] :size [614 720])]
    ;; Add ElementList component for vertical scrolling
    (comp/add-component! list-w (comp/element-list :spacing 10))
    ;; Build rows grouped by category
    (doseq [[cat cat-props] (group-by :category props)]
      ;; Category header — copy t_cathead template
      (let [hdr (cgui-core/copy-widget (xml-parser/get-widget doc "t_cathead"))]
        (when-let [txt (cgui-core/find-widget hdr "text")]
          (comp/set-text! (comp/get-textbox-component txt) cat))
        (cgui-core/add-widget! list-w hdr))
      ;; Property rows
      (doseq [p cat-props]
        (comp/list-add!
          (comp/get-widget-component list-w :element-list)
          (build-checkbox-row doc p))))
    (cgui-core/add-widget! area list-w)
    ;; Wire scrollbar → element-list progress
    ;; The scrollbar has a drag-bar component; on-drag moves the bar,
    ;; and we sync element-list progress proportionally.
    (when-let [bar (cgui-core/find-widget root "scrollbar")]
      (let [drag-comp (comp/get-widget-component bar :drag-bar)]
        ;; The XML drag-bar has lower=119.0 upper=760.0
        (events/on-drag bar
          (fn [_]
            (let [[_ sy] (cgui-core/get-pos bar)
                  bar-range (- 760.0 119.0)
                  progress (max 0.0 (min 1.0 (/ (- sy 119.0) bar-range)))
                  el (comp/get-widget-component list-w :element-list)
                  item-count (count (cgui-core/get-widgets list-w))
                  max-scroll (max 0 (- item-count 9)) ; ~9 rows visible
                  target-idx (int (* progress max-scroll))]
              ;; Set element-list progress (controls which items are visible)
              (when el
                (swap! (:state el) assoc :progress target-idx)))))))
    root))

;; --- Public ---

(defn open-settings!
  "Open the interactive settings GUI."
  [_player]
  (log/info "Opening settings")
  (client-bridge/open-simple-gui! (build-settings-gui) "Settings"))
