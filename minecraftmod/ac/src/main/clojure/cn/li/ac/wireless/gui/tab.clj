(ns cn.li.ac.wireless.gui.tab
  "Shared Wireless tab (page_wireless.xml) for all TechUI screens.

  Modes:
  - :node      -> connect a Wireless Node to a Matrix network (SSID list)
  - :generator -> connect a Generator (SolarGen) to a Wireless Node"
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]))

(defn- node-msg [action] (msg-registry/msg :node action))
(defn- gen-msg [action] (msg-registry/msg :generator action))
(defn- dev-msg [action] (msg-registry/msg :developer action))

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
   - Key icon alpha toggles on focus.

  `wireless-root` must be the `panel_wireless` widget (paths are relative to it)."
  [wireless-root {:keys [linked avail connect-fn disconnect-fn name-fn encrypted?-fn]}]
  (let [wlist (cgui/find-widget wireless-root "zone_elementlist")
        elem-template (cgui/find-widget wireless-root "zone_elementlist/element")
        connected-elem (cgui/find-widget wireless-root "elem_connected")
        btn-up (cgui/find-widget wireless-root "btn_arrowup")
        btn-down (cgui/find-widget wireless-root "btn_arrowdown")
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

    wireless-root)

(defn- wireless-panel-from-main
  [main-root]
  (or (cgui/find-widget main-root "panel_wireless") main-root))

;; ============================================================================
;; Role configuration table — single source of truth for per-role differences
;; ============================================================================

(def ^:private role-config
  {:node      {:list-msg       #(node-msg :list-networks)
               :disconnect-msg #(node-msg :disconnect)
               :connect-msg    #(node-msg :connect)
               :name-fn        (fn [t] (:ssid t))
               :connect-payload-fn (fn [payload target pass]
                                     (assoc payload :ssid (:ssid target) :password pass))
               :logo-path      "textures/guis/icons/icon_tomatrix.png"
               :logo-breathe?  true}
   :generator {:list-msg       #(gen-msg :list-nodes)
               :disconnect-msg #(gen-msg :disconnect)
               :connect-msg    #(gen-msg :connect)
               :name-fn        (fn [t] (:node-name t))
               :connect-payload-fn (fn [payload target pass]
                                     (assoc payload
                                            :node-x (:pos-x target)
                                            :node-y (:pos-y target)
                                            :node-z (:pos-z target)
                                            :password pass
                                            :need-auth? true))
               :logo-path      nil
               :logo-breathe?  true}
   :receiver  {:list-msg       #(dev-msg :list-nodes)
               :disconnect-msg #(dev-msg :disconnect)
               :connect-msg    #(dev-msg :connect)
               :name-fn        (fn [t] (:node-name t))
               :connect-payload-fn (fn [payload target pass]
                                     (assoc payload
                                            :node-x (:pos-x target)
                                            :node-y (:pos-y target)
                                            :node-z (:pos-z target)
                                            :password pass
                                            :need-auth? true))
               :logo-path      nil
               :logo-breathe?  true}})

;; ============================================================================
;; Unified rebuild installer
;; ============================================================================

(defn- install-panel-rebuild!
  "Generic rebuild loop for any wireless-panel role.
  `panel`   - the panel_wireless widget
  `payload` - base server message payload (tile position)
  `cfg`     - entry from role-config
  `opts`    - optional: {:connected-row-logo-path ...}"
  [panel payload cfg {:keys [connected-row-logo-path]}]
  (let [{:keys [list-msg disconnect-msg connect-msg name-fn connect-payload-fn]} cfg]
    (letfn [(rebuild! []
              (net-client/send-to-server
                (list-msg)
                payload
                (fn [resp]
                  (rebuild-page! panel
                                 {:linked       (:linked resp)
                                  :avail        (vec (:avail resp []))
                                  :name-fn      name-fn
                                  :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                                  :disconnect-fn (fn [_linked]
                                                   (net-client/send-to-server
                                                     (disconnect-msg) payload
                                                     (fn [_] (rebuild!))))
                                  :connect-fn   (fn [target pass]
                                                  (net-client/send-to-server
                                                    (connect-msg)
                                                    (connect-payload-fn payload target pass)
                                                    (fn [_] (rebuild!))))})
                  (when connected-row-logo-path
                    (when-let [row-logo (cgui/find-widget panel "elem_connected/icon_logo")]
                      (set-drawtexture! row-logo connected-row-logo-path))))))]
      (rebuild!))))

;; ============================================================================
;; Panel factory — one function, :role dispatch
;; ============================================================================

(defn- setup-panel-logo!
  "Apply logo texture and optional breathe effect to the panel icon_logo widget."
  [root {:keys [logo-path logo-breathe?]} override-path]
  (when-let [logo (cgui/find-widget root "icon_logo")]
    (when logo-breathe?
      (comp/add-component! logo (comp/breathe-effect)))
    (let [path (or override-path
                   (when logo-path (modid/asset-path logo-path)))]
      (when path
        (set-drawtexture! logo path)))))

(defn create-wireless-panel-by-role
  "Unified wireless panel factory.

   opts:
   - :role                  :node | :generator | :receiver  (required)
   - :container             the GUI container (must have :tile-entity)  (required)
   - :tab-logo-path         override top-left icon path
   - :connected-row-logo-path  logo for the connected-row in :receiver mode
   - :defer-initial-rebuild?   skip first list fetch until lazy activator runs"
  [{:keys [role container tab-logo-path connected-row-logo-path
           defer-initial-rebuild?]}]
  (let [cfg     (get role-config role)
        _       (when-not cfg
                  (throw (ex-info "Unknown wireless panel role" {:role role})))
        doc     (base-wireless-doc)
        root    (cgui-doc/get-widget doc "main")
        panel   (wireless-panel-from-main root)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]
    (setup-panel-logo! root cfg tab-logo-path)
    (when-not defer-initial-rebuild?
      (install-panel-rebuild! panel payload cfg
                              {:connected-row-logo-path connected-row-logo-path}))
    root))

;; ============================================================================
;; Backward-compatible named constructors (thin wrappers — kept for callers)
;; ============================================================================

(defn create-wireless-panel-node
  "Wireless tab for Wireless Node: connect node -> matrix network (SSID list)."
  [container]
  (create-wireless-panel-by-role {:role :node :container container}))

(defn create-wireless-panel-generator
  "Wireless tab for Generator (SolarGen): connect generator -> wireless node."
  [container]
  (create-wireless-panel-by-role {:role :generator :container container}))

(defn create-wireless-panel-receiver
  "Wireless tab for IF receivers: link receiver -> wireless node.

  opts:
  - :tab-logo-path              top-left tab icon
  - :connected-row-logo-path    elem_connected row icon
  - :defer-initial-rebuild?     skip first list fetch until lazy activator runs"
  [container & [{:keys [tab-logo-path connected-row-logo-path defer-initial-rebuild?]}]]
  (create-wireless-panel-by-role {:role                     :receiver
                                  :container                container
                                  :tab-logo-path            tab-logo-path
                                  :connected-row-logo-path  connected-row-logo-path
                                  :defer-initial-rebuild?   defer-initial-rebuild?}))

(defn developer-wireless-tab-lazy-activator
  "Return a zero-arg fn: first call runs install-panel-rebuild! (use with :defer-initial-rebuild?).
  `main-root` must be the root widget returned by `create-wireless-panel-receiver`."
  [main-root container connected-row-logo-path]
  (let [done? (atom false)
        pw (wireless-panel-from-main main-root)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]
    (fn []
      (when (compare-and-set! done? false true)
        (install-panel-rebuild! pw payload (:receiver role-config)
                                {:connected-row-logo-path connected-row-logo-path})))))

(defn create-embedded-developer-wireless-panel!
  "Detach `panel_wireless` from `page_wireless.xml` and attach under `host-widget` (classic `parent_right/area`).
  Same list/connect behavior as the Wireless tab, without editing `page_developer.xml`."
  [container host-widget & [{:keys [connected-row-logo-path]}]]
  (when (and container (:tile-entity container) host-widget)
    (let [doc (base-wireless-doc)
          main-root (cgui-doc/get-widget doc "main")
          panel (wireless-panel-from-main main-root)
          payload (net-helpers/tile-pos-payload (:tile-entity container))]
      (cgui/remove-widget! main-root panel)
      (cgui/add-widget! host-widget panel)
      (cgui/set-position! panel 0 0)
      ;; `panel_wireless` transform is CENTER in XML; wide `parent_right/area` would offset it.
      (cgui/set-w-align! panel :left)
      (cgui/set-h-align! panel :top)
      (install-panel-rebuild! panel payload (:receiver role-config)
                              {:connected-row-logo-path connected-row-logo-path})
      panel)))

(defn create-wireless-panel
  "Create shared wireless tab panel.

  opts:
  - :mode      :node | :generator | :receiver
  - :container the GUI container (must have :tile-entity)
  - :receiver-tab-logo-path / :receiver-connected-logo-path — only for :receiver
  - :receiver-defer-initial-rebuild? — receiver only: skip first list fetch until lazy activator (developer GUI)"
  [{:keys [mode container receiver-tab-logo-path receiver-connected-logo-path
           receiver-defer-initial-rebuild?]}]
  (case mode
    :generator (create-wireless-panel-generator container)
    :receiver (create-wireless-panel-receiver container
                {:tab-logo-path receiver-tab-logo-path
                 :connected-row-logo-path receiver-connected-logo-path
                 :defer-initial-rebuild? receiver-defer-initial-rebuild?})
    :node (create-wireless-panel-node container)
    (do
      (log/warn "Unknown wireless-tab mode:" mode ", falling back to :node")
      (create-wireless-panel-node container))))

