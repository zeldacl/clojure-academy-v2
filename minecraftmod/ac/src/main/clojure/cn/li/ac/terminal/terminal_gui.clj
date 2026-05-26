(ns cn.li.ac.terminal.terminal-gui
  "CLIENT-ONLY: Terminal GUI implementation.

  This file contains:
  - Terminal GUI layout and app grid generation
  - Client-side network message senders
  - App installation/launching logic

  Must be loaded via side-checked requiring-resolve from platform layer."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.terminal.app-registry :as app-reg]))

;; ============================================================================
;; Terminal State
;; ============================================================================

(def ^:private default-terminal-state
  {:terminal-installed? false
   :installed-apps #{}
   :available-apps []
   :loading? false
   :page 0})

(defonce ^:private terminal-state (atom {}))

(defonce ^:private active-terminal-owners (atom #{}))

(def ^:dynamic *terminal-owner* nil)

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Terminal owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- client-session-id
  [owner]
  (require-owner-value owner ":client-session-id"
                       (or (:client-session-id owner)
                           (:session-id owner)
                           runtime-hooks/*client-session-id*)))

(defn- owner-player-id
  [owner]
  (require-owner-value owner ":player-uuid"
                       (:player-uuid owner)))

(defn terminal-owner-key
  [owner]
  [(client-session-id owner)
   (or (:screen-id owner) :terminal)
   (owner-player-id owner)])

(defn- player-terminal-owner
  [player]
  (let [player-id (or (player-uuid/player-uuid player) (str player))]
    {:client-session-id (or runtime-hooks/*client-session-id*
                            [:terminal-client player-id])
     :screen-id :terminal
     :player-uuid player-id}))

(defn terminal-state-snapshot
  ([]
   (terminal-state-snapshot *terminal-owner*))
  ([owner]
   (get @terminal-state (terminal-owner-key owner) default-terminal-state)))

(defn- swap-terminal-state!
  [owner f & args]
  (let [owner-key (terminal-owner-key owner)]
    (swap! terminal-state
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-terminal-state) args))))))

(defn- activate-terminal-owner!
  [owner]
  (swap! active-terminal-owners conj (terminal-owner-key owner))
  nil)

(defn- terminal-owner-active?
  [owner]
  (contains? @active-terminal-owners (terminal-owner-key owner)))

(defn clear-terminal-state!
  [owner]
  (let [owner-key (terminal-owner-key owner)]
    (swap! terminal-state dissoc owner-key)
    (swap! active-terminal-owners disj owner-key))
  nil)

(defn reset-terminal-states-for-test!
  []
  (reset! terminal-state {})
  (reset! active-terminal-owners #{})
  nil)

;; ============================================================================
;; Network Communication
;; ============================================================================

(defn query-terminal-state!
  "Query terminal state from server."
  ([callback]
   (query-terminal-state! *terminal-owner* callback))
  ([owner callback]
  (activate-terminal-owner! owner)
  (net-client/send-to-server
    owner
    (terminal-messages/msg-id :get-state)
    {}
    (fn [response]
      (when (terminal-owner-active? owner)
        (swap-terminal-state! owner merge
               {:terminal-installed? (:terminal-installed? response)
                :installed-apps (set (:installed-apps response))
                :available-apps (:available-apps response)})
        (when callback (callback response)))))))

(defn install-terminal!
  "Send terminal installation request to server."
  ([callback]
   (install-terminal! *terminal-owner* callback))
  ([owner callback]
  (activate-terminal-owner! owner)
  (swap-terminal-state! owner assoc :loading? true)
  (net-client/send-to-server
    owner
    (terminal-messages/msg-id :install-terminal)
    {}
    (fn [response]
      (when (terminal-owner-active? owner)
        (swap-terminal-state! owner assoc :loading? false)
        (when (:success response)
          (swap-terminal-state! owner assoc :terminal-installed? true))
        (when callback (callback response)))))))

(defn install-app!
  "Send app installation request to server."
  ([app-id callback]
   (install-app! *terminal-owner* app-id callback))
  ([owner app-id callback]
  (activate-terminal-owner! owner)
  (swap-terminal-state! owner assoc :loading? true)
  (net-client/send-to-server
    owner
    (terminal-messages/msg-id :install-app)
    {:app-id (name app-id)}
    (fn [response]
      (when (terminal-owner-active? owner)
        (swap-terminal-state! owner assoc :loading? false)
        (when (:success response)
          (swap-terminal-state! owner update :installed-apps conj app-id))
        (when callback (callback response)))))))

(defn uninstall-app!
  "Send app uninstallation request to server."
  ([app-id callback]
   (uninstall-app! *terminal-owner* app-id callback))
  ([owner app-id callback]
  (activate-terminal-owner! owner)
  (net-client/send-to-server
    owner
    (terminal-messages/msg-id :uninstall-app)
    {:app-id (name app-id)}
    (fn [response]
      (when (terminal-owner-active? owner)
        (when (:success response)
          (swap-terminal-state! owner update :installed-apps disj app-id))
        (when callback (callback response)))))))

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

(def ^:private apps-per-page
  (* (:columns grid-config) (:rows grid-config)))

(defn- grid-position
  "Calculate grid position for app index."
  [index]
  (let [row (quot index (:columns grid-config))
        col (rem index (:columns grid-config))]
    [(get (:col-x grid-config) col)
     (get (:row-y grid-config) row)]))

(defn- ordered-apps
  "Stable app ordering for deterministic grid layout across rebuilds."
  []
  (->> (app-reg/list-all-apps)
       (sort-by (fn [app]
                  [(str (or (:category app) ""))
                   (str (or (:name app) ""))
                   (str (or (:id app) ""))]))
       vec))

(defn- page-count
  [apps]
  (max 1 (int (Math/ceil (/ (double (count apps)) (double apps-per-page))))))

(defn- clamp-page
  [apps page]
  (let [max-page (dec (page-count apps))]
    (-> (int (or page 0))
        (max 0)
        (min max-page))))

(defn- set-arrow-alpha!
  [root-widget path alpha]
  (when-let [w (cgui-core/find-widget root-widget path)]
    (when-let [dt (comp/get-drawtexture-component w)]
      (let [a (int (Math/round (* 255.0 (double alpha))))
            rgb 0x00FFFFFF
            color (unchecked-int (bit-or (bit-shift-left (bit-and a 0xFF) 24) rgb))]
        (swap! (:state dt) assoc :color color)))))

(defn- update-arrow-state!
  [root-widget apps page]
  (let [max-page (dec (page-count apps))
        can-up? (> page 0)
        can-down? (< page max-page)]
    (set-arrow-alpha! root-widget "arrow_up" (if can-up? 1.0 0.35))
    (set-arrow-alpha! root-widget "arrow_down" (if can-down? 1.0 0.35))))

(defn- create-app-widget
  "Create a widget for an app using the app_template from XML."
  [app index installed? on-click]
  (let [[x y] (grid-position index)
        widget (cgui-core/create-widget :pos [x y] :size [(:app-width grid-config) (:app-height grid-config)])
        ;; Create background
        bg (cgui-core/create-widget :pos [0 0] :size [151 151])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)
        ;; Create icon
        icon (cgui-core/create-widget :pos [9 32] :size [110 110])
        icon-texture (or (:icon app) (modid/asset-path "textures" "guis/apps/default/icon.png"))
        icon-comp (comp/draw-texture icon-texture [255 255 255 (if installed? 255 160)])
        _ (comp/add-component! icon icon-comp)
        ;; Create text label
        text (cgui-core/create-widget :pos [0 148] :size [151 21])
        text-comp (comp/text-box :text (:name app) :color 0xFFFFFFFF :scale 1.0)
        _ (comp/add-component! text text-comp)
        ;; Add click handler
        _ (events/on-left-click widget (fn [_] (on-click app installed?)))]

    (cgui-core/add-widget! widget bg)
    (cgui-core/add-widget! widget icon)
    (cgui-core/add-widget! widget text)
    widget))

;; ============================================================================
;; Terminal GUI Creation
;; ============================================================================

(defn- update-app-count!
  "Update the app count text."
  [root-widget]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_appcount")]
    (let [state (terminal-state-snapshot)
          installed-count (count (:installed-apps state))
          total-count (count (:available-apps state))
          apps (ordered-apps)
          page (clamp-page apps (:page state))
          total-pages (page-count apps)
          ;; Keep original count and add page context for terminal grid.
          text (str installed-count "/" total-count
                    " Applications  P"
                    (inc page)
                    "/"
                    total-pages)]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb text)))))

(defn- update-username!
  "Update the username text."
  [root-widget player]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_username")]
    (let [username (entity/player-get-name player)]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb username)))))

(defn- update-loading-indicator!
  "Update loading indicator visibility."
  [root-widget]
  (let [loading? (:loading? (terminal-state-snapshot))]
    (when-let [icon-widget (cgui-core/find-widget root-widget "icon_loading")]
      (cgui-core/set-visible! icon-widget loading?))
    (when-let [text-widget (cgui-core/find-widget root-widget "text_loading")]
      (cgui-core/set-visible! text-widget loading?))))

(declare rebuild-app-grid!)

(defn- handle-app-click
  "Handle app click - install if not installed, launch if installed."
  [app installed? player root-widget]
  (let [owner (player-terminal-owner player)
        app-id (:id app)]
    (if installed?
      ;; Launch app
      (do
        (log/info "Launching app:" app-id)
        (app-reg/launch-app app-id player))
      ;; Install app
      (do
        (log/info "Installing app:" app-id)
        (install-app! owner app-id
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
  (binding [*terminal-owner* (player-terminal-owner player)]
  (try
    ;; Remove old app widgets
    (doseq [i (range 9)]
      (when-let [old-widget (cgui-core/find-widget root-widget (str "app_" i))]
        (cgui-core/remove-widget! root-widget old-widget)))

    ;; Get all apps
        (let [state (terminal-state-snapshot)
          all-apps (ordered-apps)
          page (clamp-page all-apps (:page state))
          installed-apps (:installed-apps state)
          _ (swap-terminal-state! *terminal-owner* assoc :page page)
          offset (* page apps-per-page)
          page-apps (->> all-apps (drop offset) (take apps-per-page))]

      ;; Create new app widgets
      (doseq [[index app] (map-indexed vector page-apps)]
        (let [installed? (contains? installed-apps (:id app))
              app-widget (create-app-widget app index installed?
                                           (fn [a inst?]
                                             (handle-app-click a inst? player root-widget)))]
          (cgui-core/set-name! app-widget (str "app_" index))
          (cgui-core/add-widget! root-widget app-widget))))

    ;; Update counters
    (update-app-count! root-widget)
    (update-loading-indicator! root-widget)
    (update-arrow-state! root-widget (ordered-apps) (:page (terminal-state-snapshot)))

    (catch Exception e
      (log/error "Error rebuilding app grid:" (ex-message e))))))

(defn- change-page!
  [delta root-widget player]
  (binding [*terminal-owner* (player-terminal-owner player)]
  (let [apps (ordered-apps)
        current (:page (terminal-state-snapshot))
        next-page (clamp-page apps (+ (int (or current 0)) (int delta)))]
    (when (not= next-page current)
      (swap-terminal-state! *terminal-owner* assoc :page next-page)
      (rebuild-app-grid! root-widget player)))))

(defn- bind-navigation-controls!
  [root-widget player]
  (when-let [up (cgui-core/find-widget root-widget "arrow_up")]
    (events/unlisten! up :left-click)
    (events/on-left-click up
      (fn [_]
        (change-page! -1 root-widget player))))
  (when-let [down (cgui-core/find-widget root-widget "arrow_down")]
    (events/unlisten! down :left-click)
    (events/on-left-click down
      (fn [_]
        (change-page! 1 root-widget player)))))

(defn create-terminal-gui
  "Create terminal GUI.

  Args:
  - player: EntityPlayer

  Returns: Root CGui widget"
  [player]
  (try
    (log/info "Creating terminal GUI for player:" (entity/player-get-name player))

    ;; Load terminal.xml
    (let [owner (player-terminal-owner player)
          xml-path (modid/asset-path "guis" "terminal.xml")
          root-widget (cgui-doc/read-xml xml-path)]
		(binding [*terminal-owner* owner]

      (if-not root-widget
        (do
          (log/error "Failed to load terminal.xml")
          (cgui-core/create-widget :size [640 785]))

        (do
          ;; Update username
          (update-username! root-widget player)

          ;; Bind terminal page navigation controls from XML arrows.
          (bind-navigation-controls! root-widget player)

          ;; Query terminal state from server
          (query-terminal-state! owner
            (fn [_response]
              ;; Build app grid after state is loaded
              (rebuild-app-grid! root-widget player)))

          ;; Hide app_template (it's just a template)
          (when-let [template (cgui-core/find-widget root-widget "app_template")]
            (cgui-core/set-visible! template false))

          ;; Set up periodic refresh (every 2 seconds)
          (events/on-frame root-widget
                          (let [counter (atom 0)]
                            (fn [_]
                              (swap! counter inc)
                              (when (zero? (mod @counter 40)) ; 40 frames ≈ 2 seconds
                                (binding [*terminal-owner* owner]
                                  (update-loading-indicator! root-widget))))))

          (log/info "Terminal GUI created successfully")
          root-widget))))

    (catch Exception e
      (log/error "Error creating terminal GUI:" (ex-message e))
      (log/error "Stack trace:" (.printStackTrace e))
      ;; Return empty widget on error
      (cgui-core/create-widget :size [640 785]))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-terminal-screen
  "Create CGuiScreen for Terminal GUI."
  [player]
  (let [gui (create-terminal-gui player)]
    (cgui-screen/create-cgui-screen gui)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-terminal
  "Open terminal GUI for player."
  [player]
  (log/info "Opening terminal for player:" (entity/player-get-name player))
  (try
    ;; Query state first, then open GUI
    (let [owner (player-terminal-owner player)]
    (query-terminal-state! owner
      (fn [response]
        (if (:terminal-installed? response)
          ;; Terminal installed - open GUI via platform bridge
          (client-bridge/open-screen! :ac/terminal {:player player})
          ;; Terminal not installed - install first
          (do
            (log/info "Terminal not installed, installing...")
            (install-terminal! owner
              (fn [install-response]
                (if (:success install-response)
                  ;; Open GUI after installation via platform bridge
                  (client-bridge/open-screen! :ac/terminal {:player player})
                  (log/error "Failed to install terminal")))))))))
    (catch Exception e
      (log/error "Error opening terminal:" (ex-message e)))))

(defn init!
  "Initialize Terminal GUI module."
  []
  (log/info "Terminal GUI module initialized"))
