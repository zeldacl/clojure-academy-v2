(ns cn.li.ac.terminal.client.apps.settings
  "CLIENT-ONLY: interactive settings app ported from original AcademyCraft
  SettingsUI.  Loads settings.xml layout, populates checkbox rows from config
  descriptors, and persists toggles via Forge ConfigValue.set() + ModConfig.save().

  Uses cn.li.mcmod.platform.config-persist for loader-specific file persistence."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.platform.config-persist :as config-persist]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as xml-parser]
            [cn.li.mcmod.i18n :as i18n]
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

(defn- persist-config-value!
  "Persist a config value through the platform adapter when available."
  [domain key value]
  (config-persist/persist-config-value! domain key value))

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
      (comp/set-text! (comp/get-textbox-component text-w)
                      (or (i18n/translate (str "ac.settings.prop." (name key))) (name key))))
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
              (persist-config-value! domain key new-val)
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
    (doseq [[cat cat-props] (group-by #(get % :category) props)]
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
    ;; ---- Key binding editor (matching original PropertyElements.KEY / t_key template) ----
    (when-let [key-row (cgui-core/copy-widget (xml-parser/get-widget doc "t_key"))]
      (let [recording? (atom false)
            current-key (atom "LMENU")
            text-w (cgui-core/find-widget key-row "text")
            box-w  (cgui-core/find-widget key-row "box")]
        (when text-w
          (comp/set-text! (comp/get-textbox-component text-w) "Open Terminal"))
        (when box-w
          (comp/set-text! (comp/get-textbox-component box-w) @current-key)
          (events/on-left-click box-w
            (fn [_]
              (reset! recording? true)
              (comp/set-text! (comp/get-textbox-component box-w) "PRESS")
              (events/on-key-press box-w
                (fn key-capture [evt]
                  (when @recording?
                    (reset! recording? false)
                    (let [key-name (or (:key-name evt)
                                       (str "KEY_" (:keyCode evt)))]
                      (comp/set-text! (comp/get-textbox-component box-w) key-name)
                      (reset! current-key key-name)
                      (events/unlisten! box-w :key-press))))))))
        (comp/list-add!
          (comp/get-widget-component list-w :element-list)
          key-row)))
    ;; ---- Callback button (matching original PropertyElements.CALLBACK / t_callback) ----
    (when-let [cb-row (cgui-core/copy-widget (xml-parser/get-widget doc "t_callback"))]
      (let [text-w (cgui-core/find-widget cb-row "text")
            box-w  (cgui-core/find-widget cb-row "box")]
        (when text-w
          (comp/set-text! (comp/get-textbox-component text-w) "Restore Defaults"))
        (when box-w
          (comp/set-text! (comp/get-textbox-component box-w) "Reset")
          (events/on-left-click box-w
            (fn [_]
              (doseq [p props]
                (persist-config-value! (:domain p) (:key p) false))
              (log/info "Settings: restored defaults"))))
        (comp/list-add!
          (comp/get-widget-component list-w :element-list)
          cb-row)))
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
