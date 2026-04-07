(ns cn.li.ac.terminal.terminal-gui
  "CLIENT-ONLY: Terminal GUI implementation.

  This file contains:
  - Terminal GUI layout and app grid generation
  - Client-side network message senders
  - App installation/launching logic

  Must be loaded via side-checked requiring-resolve from platform layer."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.terminal.app-registry :as app-reg]
            [cn.li.ac.terminal.network :as term-net]))

;; ============================================================================
;; Terminal State
;; ============================================================================

(defonce terminal-state
  (atom {:terminal-installed? false
         :installed-apps #{}
         :available-apps []
         :loading? false}))

;; ============================================================================
;; Network Communication
;; ============================================================================

(defn- msg-id [action]
  (term-net/msg-id action))

(defn query-terminal-state!
  "Query terminal state from server."
  [callback]
  (net-client/send-to-server
    (msg-id :get-state)
    {}
    (fn [response]
      (swap! terminal-state merge
             {:terminal-installed? (:terminal-installed? response)
              :installed-apps (set (:installed-apps response))
              :available-apps (:available-apps response)})
      (when callback (callback response)))))

(defn install-terminal!
  "Send terminal installation request to server."
  [callback]
  (swap! terminal-state assoc :loading? true)
  (net-client/send-to-server
    (msg-id :install-terminal)
    {}
    (fn [response]
      (swap! terminal-state assoc :loading? false)
      (when (:success response)
        (swap! terminal-state assoc :terminal-installed? true))
      (when callback (callback response)))))

(defn install-app!
  "Send app installation request to server."
  [app-id callback]
  (swap! terminal-state assoc :loading? true)
  (net-client/send-to-server
    (msg-id :install-app)
    {:app-id (name app-id)}
    (fn [response]
      (swap! terminal-state assoc :loading? false)
      (when (:success response)
        (swap! terminal-state update :installed-apps conj app-id))
      (when callback (callback response)))))

(defn uninstall-app!
  "Send app uninstallation request to server."
  [app-id callback]
  (net-client/send-to-server
    (msg-id :uninstall-app)
    {:app-id (name app-id)}
    (fn [response]
      (when (:success response)
        (swap! terminal-state update :installed-apps disj app-id))
      (when callback (callback response)))))

;; ============================================================================
;; App Grid Layout
;; ============================================================================

(def ^:private grid-config
  {:columns 3
   :rows 3
   :col-x [30 211 392]
   :row-y [155 315 475]
   :app-width 151
   :app-height 151})

(defn- grid-position
  "Calculate grid position for app index."
  [index]
  (let [row (quot index (:columns grid-config))
        col (rem index (:columns grid-config))]
    [(get (:col-x grid-config) col)
     (get (:row-y grid-config) row)]))

(defn- create-app-widget
  "Create a widget for an app using the app_template from XML."
  [app index installed? on-click]
  (let [[x y] (grid-position index)
        widget (cgui/create-widget :pos [x y] :size [(:app-width grid-config) (:app-height grid-config)])
        ;; Create background
        bg (cgui/create-widget :pos [0 0] :size [151 151])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)
        ;; Create icon
        icon (cgui/create-widget :pos [9 32] :size [110 110])
        icon-texture (or (:icon app) "academy:textures/guis/apps/default/icon.png")
        icon-comp (comp/draw-texture icon-texture [255 255 255 (if installed? 255 160)])
        _ (comp/add-component! icon icon-comp)
        ;; Create text label
        text (cgui/create-widget :pos [0 148] :size [151 21])
        text-comp (comp/text-box :text (:name app) :color 0xFFFFFFFF :scale 1.0)
        _ (comp/add-component! text text-comp)
        ;; Add click handler
        _ (events/on-left-click widget (fn [_] (on-click app installed?)))]

    (cgui/add-widget! widget bg)
    (cgui/add-widget! widget icon)
    (cgui/add-widget! widget text)
    widget))

;; ============================================================================
;; Terminal GUI Creation
;; ============================================================================

(defn- update-app-count!
  "Update the app count text."
  [root-widget]
  (when-let [text-widget (cgui/find-widget root-widget "text_appcount")]
    (let [installed-count (count (:installed-apps @terminal-state))
          total-count (count (:available-apps @terminal-state))
          ;; Get current time (simplified - just show count)
          text (str installed-count "/" total-count " Applications")]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb text)))))

(defn- update-username!
  "Update the username text."
  [root-widget player]
  (when-let [text-widget (cgui/find-widget root-widget "text_username")]
    (let [username (entity/player-get-name player)]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb username)))))

(defn- update-loading-indicator!
  "Update loading indicator visibility."
  [root-widget]
  (let [loading? (:loading? @terminal-state)]
    (when-let [icon-widget (cgui/find-widget root-widget "icon_loading")]
      (cgui/set-visible! icon-widget loading?))
    (when-let [text-widget (cgui/find-widget root-widget "text_loading")]
      (cgui/set-visible! text-widget loading?))))

(declare rebuild-app-grid!)

(defn- handle-app-click
  "Handle app click - install if not installed, launch if installed."
  [app installed? player root-widget]
  (let [app-id (:id app)]
    (if installed?
      ;; Launch app
      (do
        (log/info "Launching app:" app-id)
        (app-reg/launch-app app-id player))
      ;; Install app
      (do
        (log/info "Installing app:" app-id)
        (install-app! app-id
                      (fn [response]
                        (if (:success response)
                          (do
                            (log/info "App installed successfully:" app-id)
                            ;; Refresh GUI
                            (rebuild-app-grid! root-widget player))
                          (log/error "Failed to install app:" app-id))))))))

(defn- rebuild-app-grid!
  "Rebuild the app grid with current state."
  [root-widget player]
  (try
    ;; Remove old app widgets
    (doseq [i (range 9)]
      (when-let [old-widget (cgui/find-widget root-widget (str "app_" i))]
        (cgui/remove-widget! root-widget old-widget)))

    ;; Get all apps
    (let [all-apps (app-reg/list-all-apps)
          installed-apps (:installed-apps @terminal-state)]

      ;; Create new app widgets
      (doseq [[index app] (map-indexed vector (take 9 all-apps))]
        (let [installed? (contains? installed-apps (:id app))
              app-widget (create-app-widget app index installed?
                                           (fn [a inst?]
                                             (handle-app-click a inst? player root-widget)))]
          (cgui/set-name! app-widget (str "app_" index))
          (cgui/add-widget! root-widget app-widget))))

    ;; Update counters
    (update-app-count! root-widget)
    (update-loading-indicator! root-widget)

    (catch Exception e
      (log/error "Error rebuilding app grid:" (ex-message e)))))

(defn create-terminal-gui
  "Create terminal GUI.

  Args:
  - player: EntityPlayer

  Returns: Root CGui widget"
  [player]
  (try
    (log/info "Creating terminal GUI for player:" (entity/player-get-name player))

    ;; Load terminal.xml
    (let [xml-path (modid/asset-path "guis" "terminal.xml")
          root-widget (cgui-doc/read-xml xml-path)]

      (if-not root-widget
        (do
          (log/error "Failed to load terminal.xml")
          (cgui/create-widget :size [640 785]))

        (do
          ;; Update username
          (update-username! root-widget player)

          ;; Query terminal state from server
          (query-terminal-state!
            (fn [_response]
              ;; Build app grid after state is loaded
              (rebuild-app-grid! root-widget player)))

          ;; Hide app_template (it's just a template)
          (when-let [template (cgui/find-widget root-widget "app_template")]
            (cgui/set-visible! template false))

          ;; Set up periodic refresh (every 2 seconds)
          (events/on-frame root-widget
                          (let [counter (atom 0)]
                            (fn [_]
                              (swap! counter inc)
                              (when (zero? (mod @counter 40)) ; 40 frames ≈ 2 seconds
                                (update-loading-indicator! root-widget)))))

          (log/info "Terminal GUI created successfully")
          root-widget)))

    (catch Exception e
      (log/error "Error creating terminal GUI:" (ex-message e))
      (log/error "Stack trace:" (.printStackTrace e))
      ;; Return empty widget on error
      (cgui/create-widget :size [640 785]))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-terminal-screen
  "Create CGuiScreen for Terminal GUI."
  [player]
  (let [gui (create-terminal-gui player)]
    (cgui/create-cgui-screen gui)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-terminal
  "Open terminal GUI for player."
  [player]
  (log/info "Opening terminal for player:" (entity/player-get-name player))
  (try
    ;; Query state first, then open GUI
    (query-terminal-state!
      (fn [response]
        (if (:terminal-installed? response)
          ;; Terminal installed - open GUI via platform bridge
          (when-let [open-fn (requiring-resolve 'cn.li.forge1201.client.terminal-screen-bridge/open-terminal-screen!)]
            (open-fn player))
          ;; Terminal not installed - install first
          (do
            (log/info "Terminal not installed, installing...")
            (install-terminal!
              (fn [install-response]
                (if (:success install-response)
                  ;; Open GUI after installation via platform bridge
                  (when-let [open-fn (requiring-resolve 'cn.li.forge1201.client.terminal-screen-bridge/open-terminal-screen!)]
                    (open-fn player))
                  (log/error "Failed to install terminal"))))))))
    (catch Exception e
      (log/error "Error opening terminal:" (ex-message e)))))

(defn init!
  "Initialize Terminal GUI module."
  []
  (log/info "Terminal GUI module initialized"))
