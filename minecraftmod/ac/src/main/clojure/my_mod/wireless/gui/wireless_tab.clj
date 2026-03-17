(ns my-mod.wireless.gui.wireless-tab
  "Shared Wireless tab (page_wireless.xml) for all TechUI screens.

  Modes:
  - :node      -> connect a Wireless Node to a Matrix network (SSID list)
  - :generator -> connect a Generator (SolarGen) to a Wireless Node"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.config.modid :as modid]
            [my-mod.network.client :as net-client]
            [my-mod.util.log :as log]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.wireless.gui.node-messages :as node-msgs]
            [my-mod.wireless.gui.generator-messages :as gen-msgs]))

(defn- widget-textbox [widget] (comp/get-textbox-component widget))
(defn- widget-drawtexture [widget] (comp/get-drawtexture-component widget))

(defn- set-textbox-text! [widget text]
  (when-let [tb (widget-textbox widget)]
    (comp/set-text! tb text)))

(defn- set-drawtexture! [widget texture-path]
  (when-let [dt (widget-drawtexture widget)]
    (comp/set-texture! dt texture-path)))

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

(defn create-wireless-panel-node
  "Wireless tab for Wireless Node: connect node -> matrix network (SSID list)."
  [container]
  (let [doc (base-wireless-doc)
        root (cgui-doc/get-widget doc "main")
        wlist (cgui/find-widget root "panel_wireless/zone_elementlist")
        elem-template (cgui/find-widget root "panel_wireless/zone_elementlist/element")
        connected-elem (cgui/find-widget root "panel_wireless/elem_connected")
        btn-up (cgui/find-widget root "panel_wireless/btn_arrowup")
        btn-down (cgui/find-widget root "panel_wireless/btn_arrowdown")
        elist (comp/element-list :spacing 2)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]

    (when wlist (comp/add-component! wlist elist))
    (ensure-template-hidden! elem-template)
    (attach-scroll-buttons! btn-up btn-down elist)

    (letfn [(update-connected! [linked?]
              (when connected-elem
                (let [icon-connect (cgui/find-widget connected-elem "icon_connect")
                      text-name (cgui/find-widget connected-elem "text_name")]
                  (set-textbox-text! text-name (if linked? "Connected" "Not Connected"))
                  (set-drawtexture! icon-connect
                    (if linked?
                      (modid/asset-path "textures" "guis/icons/icon_connected.png")
                      (modid/asset-path "textures" "guis/icons/icon_unconnected.png"))))))

            (query-linked! []
              (net-client/send-to-server
                (node-msgs/msg :get-status)
                payload
                (fn [response]
                  (update-connected! (boolean (:linked response))))))

            (connect-network! [ssid pass]
              (net-client/send-to-server
                (node-msgs/msg :connect)
                (assoc payload :ssid ssid :password pass)
                (fn [_]
                  (query-networks!)
                  (query-linked!))))

            (disconnect-network! []
              (net-client/send-to-server
                (node-msgs/msg :disconnect)
                payload
                (fn [_]
                  (query-networks!)
                  (query-linked!))))

            (build-element! [net]
              (when elem-template
                (let [elem (cgui/copy-widget elem-template)
                      ssid (:ssid net)
                      encrypted? (boolean (:is-encrypted? net))
                      text-name (cgui/find-widget elem "text_name")
                      icon-key (cgui/find-widget elem "icon_key")
                      input-pass (cgui/find-widget elem "input_pass")
                      icon-connect (cgui/find-widget elem "icon_connect")
                      pass-box (when input-pass (widget-textbox input-pass))]

                  (set-textbox-text! text-name (str ssid))

                  (if encrypted?
                    (do
                      (when icon-key (cgui/set-visible! icon-key true))
                      (when input-pass (cgui/set-visible! input-pass true)))
                    (do
                      (when icon-key (cgui/set-visible! icon-key false))
                      (when input-pass (cgui/set-visible! input-pass false))))

                  (when icon-connect
                    (events/on-left-click icon-connect
                      (fn [_]
                        (let [pwd (if (and encrypted? pass-box)
                                    (comp/get-text pass-box)
                                    "")]
                          (connect-network! ssid pwd)
                          (when pass-box (comp/set-text! pass-box ""))))))

                  (when elist (comp/list-add! elist elem)))))

            (rebuild-list! [nets]
              (when elist
                (comp/list-clear! elist)
                (doseq [net nets]
                  (build-element! net))))

            (query-networks! []
              (net-client/send-to-server
                (node-msgs/msg :list-networks)
                payload
                (fn [response]
                  (rebuild-list! (vec (:networks response []))))))]

      (when connected-elem
        (let [icon-connect (cgui/find-widget connected-elem "icon_connect")]
          (when icon-connect
            (events/on-left-click icon-connect (fn [_] (disconnect-network!))))))

      (query-networks!)
      (query-linked!))

    root))

(defn create-wireless-panel-generator
  "Wireless tab for Generator (SolarGen): connect generator -> wireless node."
  [container]
  (let [doc (base-wireless-doc)
        root (cgui-doc/get-widget doc "main")
        wlist (cgui/find-widget root "panel_wireless/zone_elementlist")
        elem-template (cgui/find-widget root "panel_wireless/zone_elementlist/element")
        connected-elem (cgui/find-widget root "panel_wireless/elem_connected")
        btn-up (cgui/find-widget root "panel_wireless/btn_arrowup")
        btn-down (cgui/find-widget root "panel_wireless/btn_arrowdown")
        elist (comp/element-list :spacing 2)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]

    (when wlist (comp/add-component! wlist elist))
    (ensure-template-hidden! elem-template)
    (attach-scroll-buttons! btn-up btn-down elist)

    (letfn [(update-connected! [linked? node-name]
              (when connected-elem
                (let [icon-connect (cgui/find-widget connected-elem "icon_connect")
                      text-name (cgui/find-widget connected-elem "text_name")]
                  (set-textbox-text! text-name
                                     (if linked?
                                       (str "Connected: " (or node-name "Node"))
                                       "Not Connected"))
                  (set-drawtexture! icon-connect
                    (if linked?
                      (modid/asset-path "textures" "guis/icons/icon_connected.png")
                      (modid/asset-path "textures" "guis/icons/icon_unconnected.png"))))))

            (query-linked! []
              (net-client/send-to-server
                (gen-msgs/msg :get-status)
                payload
                (fn [response]
                  (update-connected! (boolean (:linked response)) (:node-name response)))))

            (connect-node! [node-x node-y node-z pass need-auth?]
              (net-client/send-to-server
                (gen-msgs/msg :connect)
                (assoc payload
                       :node-x node-x :node-y node-y :node-z node-z
                       :password pass
                       :need-auth? (boolean need-auth?))
                (fn [_]
                  (query-nodes!)
                  (query-linked!))))

            (disconnect-node! []
              (net-client/send-to-server
                (gen-msgs/msg :disconnect)
                payload
                (fn [_]
                  (query-nodes!)
                  (query-linked!))))

            (build-element! [node]
              (when elem-template
                (let [elem (cgui/copy-widget elem-template)
                      node-name (:node-name node)
                      encrypted? (boolean (:is-encrypted? node))
                      nx (:pos-x node) ny (:pos-y node) nz (:pos-z node)
                      text-name (cgui/find-widget elem "text_name")
                      icon-key (cgui/find-widget elem "icon_key")
                      input-pass (cgui/find-widget elem "input_pass")
                      icon-connect (cgui/find-widget elem "icon_connect")
                      pass-box (when input-pass (widget-textbox input-pass))]

                  (set-textbox-text! text-name (str node-name))

                  (if encrypted?
                    (do
                      (when icon-key (cgui/set-visible! icon-key true))
                      (when input-pass (cgui/set-visible! input-pass true)))
                    (do
                      (when icon-key (cgui/set-visible! icon-key false))
                      (when input-pass (cgui/set-visible! input-pass false))))

                  (when icon-connect
                    (events/on-left-click icon-connect
                      (fn [_]
                        (let [pwd (if (and encrypted? pass-box)
                                    (comp/get-text pass-box)
                                    "")]
                          (connect-node! nx ny nz pwd encrypted?)
                          (when pass-box (comp/set-text! pass-box ""))))))

                  (when elist (comp/list-add! elist elem)))))

            (rebuild-list! [nodes]
              (when elist
                (comp/list-clear! elist)
                (doseq [n nodes]
                  (when (and (number? (:pos-x n)) (number? (:pos-y n)) (number? (:pos-z n)))
                    (build-element! n)))))

            (query-nodes! []
              (net-client/send-to-server
                (gen-msgs/msg :list-nodes)
                payload
                (fn [response]
                  (rebuild-list! (vec (:nodes response []))))))]

      (when connected-elem
        (let [icon-connect (cgui/find-widget connected-elem "icon_connect")]
          (when icon-connect
            (events/on-left-click icon-connect (fn [_] (disconnect-node!))))))

      (query-nodes!)
      (query-linked!))

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

