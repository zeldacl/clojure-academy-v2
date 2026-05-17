(ns cn.li.ac.wireless.gui.component.tabs
  "Wireless tab UI component - pure rendering.

  Key design principle: **This component ONLY renders UI**.
  - No state management (state passed as parameter)
  - No event handling logic (events published to bus)
  - Can be tested by inspecting output

  Rendering is delegated to platform-specific adapters."
  (:require [cn.li.ac.wireless.gui.protocol :as proto]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.gui.component.widget-helpers :as wh]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; UI Construction
;; ============================================================================

(defn- base-wireless-doc []
  (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_wireless.xml")))

(defn- wireless-panel-from-main [main-root]
  (or (cgui-core/find-widget main-root "panel_wireless") main-root))

(defn- ensure-template-hidden! [elem-template]
  (when elem-template
    (cgui-core/set-visible! elem-template false)))

(defn- attach-scroll-buttons! [btn-up btn-down elist]
  (when (and btn-up elist)
    (events/on-left-click btn-up (fn [_] (comp/list-progress-last! elist))))
  (when (and btn-down elist)
    (events/on-left-click btn-down (fn [_] (comp/list-progress-next! elist)))))

;; ============================================================================
;; Panel Rendering
;; ============================================================================

(defn rebuild-page!
  "Rebuild wireless panel UI from current state.

  `wireless-root` must be the panel_wireless widget.

  Args:
    wireless-root: panel_wireless widget
    state: {:linked network-or-nil
            :avail [networks]
            :name-fn (network) -> string
            :encrypted?-fn (network) -> boolean
            :connect-fn (network password) -> nil
            :disconnect-fn (network) -> nil}

  Returns:
    wireless-root"
  [wireless-root {:keys [linked avail connect-fn disconnect-fn name-fn encrypted?-fn]}]
  (let [wlist (cgui-core/find-widget wireless-root "zone_elementlist")
        elem-template (cgui-core/find-widget wireless-root "zone_elementlist/element")
        connected-elem (cgui-core/find-widget wireless-root "elem_connected")
        btn-up (cgui-core/find-widget wireless-root "btn_arrowup")
        btn-down (cgui-core/find-widget wireless-root "btn_arrowdown")
        elist (comp/element-list :spacing 2)
        linked-atom (atom linked)]

    (when wlist
      (comp/add-component! wlist elist))
    (ensure-template-hidden! elem-template)
    (attach-scroll-buttons! btn-up btn-down elist)

    (when connected-elem
      (let [icon-connect (cgui-core/find-widget connected-elem "icon_connect")
            icon-logo (cgui-core/find-widget connected-elem "icon_logo")
            text-name (cgui-core/find-widget connected-elem "text_name")
            connected? (some? linked)
            alpha (if connected? 1.0 0.6)
            name (if connected? (name-fn linked) "Not Connected")]
        (reset! linked-atom linked)
        (when icon-connect
          (wh/set-drawtexture! icon-connect
                            (if connected?
                              (modid/asset-path "textures" "guis/icons/icon_connected.png")
                              (modid/asset-path "textures" "guis/icons/icon_unconnected.png")))
          (wh/set-drawtexture-color! icon-connect (wh/alpha-argb 0xFFFFFFFF alpha))
          (wh/set-tint-enabled! icon-connect connected?)
          (events/on-left-click icon-connect
            (fn [_]
              (when-let [t @linked-atom]
                (disconnect-fn t)))))
        (when icon-logo
          (wh/set-drawtexture-color! icon-logo (wh/alpha-argb 0xFFFFFFFF alpha)))
        (when text-name
          (wh/set-textbox-text! text-name name))))

    (when elist
      (comp/list-clear! elist)
      (doseq [target avail]
        (when elem-template
          (let [elem (cgui-core/copy-widget elem-template)
                text-name (cgui-core/find-widget elem "text_name")
                icon-key (cgui-core/find-widget elem "icon_key")
                input-pass (cgui-core/find-widget elem "input_pass")
                icon-connect (cgui-core/find-widget elem "icon_connect")
                pass-box (when input-pass (wh/widget-textbox input-pass))
                encrypted? (boolean (encrypted?-fn target))]

            (when text-name
              (wh/set-textbox-text! text-name (name-fn target)))

            (if encrypted?
              (do
                (when icon-key
                  (cgui-core/set-visible! icon-key true)
                  (wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 0.6)))
                (when input-pass
                  (cgui-core/set-visible! input-pass true))
                (when (and input-pass pass-box)
                  (events/on-confirm-input pass-box
                    (fn [_]
                      (let [pwd (comp/get-text pass-box)]
                        (connect-fn target pwd)
                        (comp/set-text! pass-box ""))))
                  (events/on-gain-focus input-pass
                    (fn [_]
                      (when icon-key
                        (wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 1.0)))))
                  (events/on-lost-focus input-pass
                    (fn [_]
                      (when icon-key
                        (wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 0.6)))))))
              (do
                (when input-pass (cgui-core/set-visible! input-pass false))
                (when icon-key (cgui-core/set-visible! icon-key false))))

            (when icon-connect
              (events/on-left-click icon-connect
                (fn [_]
                  (let [pwd (if (and encrypted? pass-box) (comp/get-text pass-box) "")]
                    (connect-fn target pwd)
                    (when pass-box (comp/set-text! pass-box ""))))))

            (comp/list-add! elist elem)))))

    wireless-root))

;; ============================================================================
;; Tab Logo Helpers
;; ============================================================================

(defn set-tab-logo!
  "Set tab logo icon texture.

  Args:
    panel: panel_wireless widget or root widget
    texture-path: string - texture asset path

  Returns:
    nil"
  [panel texture-path]
  (when-let [logo (cgui-core/find-widget panel "icon_logo")]
    (wh/set-drawtexture! logo texture-path)))

;; ============================================================================
;; Factory for Creating Wireless Panel
;; ============================================================================

(defn create-wireless-panel
  "Create wireless panel widget with minimal setup.

  Args:
    opts: {:tab-logo-path string (optional)}

  Returns:
    panel_wireless widget ready for rebuild-page!"
  [& [{:keys [tab-logo-path]}]]
  (let [doc (base-wireless-doc)
        root (cgui-doc/get-widget doc "main")
        pw (wireless-panel-from-main root)]
    (when tab-logo-path
      (set-tab-logo! pw tab-logo-path))
    pw))

;; ============================================================================
;; Rendering Protocols
;; ============================================================================

(extend-protocol proto/IUIComponent
  Object
  (render [this state event-bus]
    nil)
  (get-layout [this]
    {:width 256 :height 256})
  (on-event [this event-data]
    false))

;; ============================================================================
;; Testing Helpers
;; ============================================================================

(defn create-mock-panel
  "Create mock panel for testing rendering logic.

  Returns:
    {:panel mock-panel :state atom :events atom}"
  []
  (let [state (atom {:linked nil :avail [] :name-fn (fn [n] (:ssid n))})
        events (atom [])]
    {:panel (reify proto/IUIComponent
              (render [this st eb]
                (swap! events conj [:render st]))
              (get-layout [this]
                {:width 256 :height 256})
              (on-event [this ed]
                (swap! events conj [:event ed])
                true))
     :state state
     :events events}))