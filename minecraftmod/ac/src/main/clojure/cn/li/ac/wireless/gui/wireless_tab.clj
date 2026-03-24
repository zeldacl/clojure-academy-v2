(ns cn.li.ac.wireless.gui.wireless-tab
  "Shared Wireless tab (page_wireless.xml) for all TechUI screens.

  Modes:
  - :node      -> connect a Wireless Node to a Matrix network (SSID list)
  - :generator -> connect a Generator (SolarGen) to a Wireless Node"
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.cgui-document :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.ac.wireless.gui.message-registry :as msg-registry]))

(defn- node-msg [action] (msg-registry/msg :node action))
(defn- gen-msg [action] (msg-registry/msg :generator action))

(defn- widget-textbox [widget] (comp/get-textbox-component widget))
(defn- widget-drawtexture [widget] (comp/get-drawtexture-component widget))

(defn- set-textbox-text! [widget text]
  (when-let [tb (widget-textbox widget)]
    (comp/set-text! tb text)))

(defn- set-drawtexture! [widget texture-path]
  (when-let [dt (widget-drawtexture widget)]
    (comp/set-texture! dt texture-path)))

(defn- set-drawtexture-color!
  [widget argb]
  (when-let [dt (widget-drawtexture widget)]
    (swap! (:state dt) assoc :color (unchecked-int argb))))

(defn- set-tint-enabled!
  [widget enabled?]
  (when-let [t (comp/get-tint-component widget)]
    (swap! (:state t) assoc :enabled (boolean enabled?))))

(defn- alpha-argb
  [argb alpha-f]
  (let [a (int (Math/round (* 255.0 (double alpha-f))))
        rgb (bit-and (long argb) 0x00FFFFFF)]
    (unchecked-int (bit-or (bit-shift-left (bit-and a 0xFF) 24) rgb))))

(defn- ensure-template-hidden! [elem-template]
  (when elem-template
    (cgui/set-visible! elem-template false)))

(defn- attach-scroll-buttons! [btn-up btn-down elist]
  (when (and btn-up elist)
    (events/on-left-click btn-up (fn [_] (comp/list-progress-last! elist))))
  (when (and btn-down elist)
    (events/on-left-click btn-down (fn [_] (comp/list-progress-next! elist)))))

(defn- base-wireless-doc []
  (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_wireless.xml")))

(defn- rebuild-page!
  "Port of AcademyCraft WirelessPage.rebuildPage behavior:
   - Connected element shows icon+name; connect icon click disconnects only if linked.
   - Avail list: encrypted shows pass box and key; else hides them.
   - Pass box confirm triggers connect.
   - Key icon alpha toggles on focus."
  [root {:keys [linked avail connect-fn disconnect-fn name-fn encrypted?-fn]}]
  (let [wlist (cgui/find-widget root "panel_wireless/zone_elementlist")
        elem-template (cgui/find-widget root "panel_wireless/zone_elementlist/element")
        connected-elem (cgui/find-widget root "panel_wireless/elem_connected")
        btn-up (cgui/find-widget root "panel_wireless/btn_arrowup")
        btn-down (cgui/find-widget root "panel_wireless/btn_arrowdown")
        elist (comp/element-list :spacing 2)
        linked-atom (atom linked)]

    (when wlist
      (comp/add-component! wlist elist))
    (ensure-template-hidden! elem-template)
    (attach-scroll-buttons! btn-up btn-down elist)

    (when connected-elem
      (let [icon-connect (cgui/find-widget connected-elem "icon_connect")
            icon-logo (cgui/find-widget connected-elem "icon_logo")
            text-name (cgui/find-widget connected-elem "text_name")
            connected? (some? linked)
            alpha (if connected? 1.0 0.6)
            name (if connected? (name-fn linked) "Not Connected")]
        (reset! linked-atom linked)
        (when icon-connect
          (set-drawtexture! icon-connect
                            (if connected?
                              (modid/asset-path "textures" "guis/icons/icon_connected.png")
                              (modid/asset-path "textures" "guis/icons/icon_unconnected.png")))
          (set-drawtexture-color! icon-connect (alpha-argb 0xFFFFFFFF alpha))
          (set-tint-enabled! icon-connect connected?)
          (events/on-left-click icon-connect
            (fn [_]
              (when-let [t @linked-atom]
                (disconnect-fn t)))))
        (when icon-logo
          (set-drawtexture-color! icon-logo (alpha-argb 0xFFFFFFFF alpha)))
        (when text-name
          (set-textbox-text! text-name name))))

    (when elist
      (comp/list-clear! elist)
      (doseq [target avail]
        (when elem-template
          (let [elem (cgui/copy-widget elem-template)
                text-name (cgui/find-widget elem "text_name")
                icon-key (cgui/find-widget elem "icon_key")
                input-pass (cgui/find-widget elem "input_pass")
                icon-connect (cgui/find-widget elem "icon_connect")
                pass-box (when input-pass (widget-textbox input-pass))
                encrypted? (boolean (encrypted?-fn target))]

            (when text-name
              (set-textbox-text! text-name (name-fn target)))

            (if encrypted?
              (do
                (when icon-key
                  (cgui/set-visible! icon-key true)
                  (set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 0.6)))

                (when input-pass
                  (cgui/set-visible! input-pass true))

                (when (and input-pass pass-box)
                  ;; enter to confirm
                  (events/on-confirm-input pass-box
                    (fn [_]
                      (let [pwd (comp/get-text pass-box)]
                        (connect-fn target pwd)
                        (comp/set-text! pass-box ""))))
                  ;; focus brightens key icon
                  (events/on-gain-focus input-pass
                    (fn [_]
                      (when icon-key
                        (set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 1.0)))))
                  (events/on-lost-focus input-pass
                    (fn [_]
                      (when icon-key
                        (set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 0.6)))))))
              (do
                (when input-pass (cgui/set-visible! input-pass false))
                (when icon-key (cgui/set-visible! icon-key false))))

            (when icon-connect
              (events/on-left-click icon-connect
                (fn [_]
                  (let [pwd (if (and encrypted? pass-box) (comp/get-text pass-box) "")]
                    (connect-fn target pwd)
                    (when pass-box (comp/set-text! pass-box ""))))))

            (comp/list-add! elist elem))))))

    root)

(defn create-wireless-panel-node
  "Wireless tab for Wireless Node: connect node -> matrix network (SSID list)."
  [container]
  (let [doc (base-wireless-doc)
        root (cgui-doc/get-widget doc "main")
        payload (net-helpers/tile-pos-payload (:tile-entity container))]

    ;; nodePage sets icon_logo to toMatrixIcon
    (when-let [logo (cgui/find-widget root "icon_logo")]
      (comp/add-component! logo (comp/breathe-effect))
      (set-drawtexture! logo (modid/asset-path "textures" "guis/icons/icon_tomatrix.png")))

    (letfn [(rebuild! []
              (net-client/send-to-server
                (node-msg :list-networks)
                payload
                (fn [resp]
                  (rebuild-page! root
                                 {:linked (:linked resp)
                                  :avail (vec (:avail resp []))
                                  :name-fn (fn [t] (:ssid t))
                                  :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                                  :disconnect-fn (fn [_linked]
                                                   (net-client/send-to-server
                                                     (node-msg :disconnect)
                                                     payload
                                                     (fn [_] (rebuild!))))
                                  :connect-fn (fn [target pass]
                                                (net-client/send-to-server
                                                  (node-msg :connect)
                                                  (assoc payload :ssid (:ssid target) :password pass)
                                                  (fn [_] (rebuild!))))}))))]
      (rebuild!))

    root))

(defn create-wireless-panel-generator
  "Wireless tab for Generator (SolarGen): connect generator -> wireless node."
  [container]
  (let [doc (base-wireless-doc)
        root (cgui-doc/get-widget doc "main")
        payload (net-helpers/tile-pos-payload (:tile-entity container))]
    (when-let [logo (cgui/find-widget root "icon_logo")]
      (comp/add-component! logo (comp/breathe-effect)))

    (letfn [(rebuild! []
              (net-client/send-to-server
                (gen-msg :list-nodes)
                payload
                (fn [resp]
                  (rebuild-page! root
                                 {:linked (:linked resp)
                                  :avail (vec (:avail resp []))
                                  :name-fn (fn [t] (:node-name t))
                                  :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                                  :disconnect-fn (fn [_linked]
                                                   (net-client/send-to-server
                                                     (gen-msg :disconnect)
                                                     payload
                                                     (fn [_] (rebuild!))))
                                  :connect-fn (fn [target pass]
                                                (net-client/send-to-server
                                                  (gen-msg :connect)
                                                  (assoc payload
                                                         :node-x (:pos-x target)
                                                         :node-y (:pos-y target)
                                                         :node-z (:pos-z target)
                                                         :password pass
                                                         :need-auth? true)
                                                  (fn [_] (rebuild!))))}))))]
      (rebuild!))

    root))

(defn create-wireless-panel
  "Create shared wireless tab panel.

  opts:
  - :mode      :node or :generator
  - :container the GUI container (must have :tile-entity)"
  [{:keys [mode container]}]
  (case mode
    :generator (create-wireless-panel-generator container)
    :node (create-wireless-panel-node container)
    (do
      (log/warn "Unknown wireless-tab mode:" mode ", falling back to :node")
      (create-wireless-panel-node container))))

