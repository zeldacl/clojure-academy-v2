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

(def ^:private default-terminal-runtime-state
  {:next-generation 1
   :owners {}})

(defn create-terminal-runtime
  []
  {::runtime ::terminal-runtime
   :runtime-state* (atom default-terminal-runtime-state)})

(defonce ^:private installed-terminal-runtime
  (create-terminal-runtime))

(def ^:dynamic *terminal-runtime*
  installed-terminal-runtime)

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

(defn- terminal-runtime?
  [runtime]
  (and (map? runtime)
       (= ::terminal-runtime (::runtime runtime))
       (some? (:runtime-state* runtime))))

(defn call-with-terminal-runtime
  [runtime f]
  (when-not (terminal-runtime? runtime)
    (throw (ex-info "Expected terminal runtime"
                    {:runtime runtime})))
  (binding [*terminal-runtime* runtime]
    (f)))

(defmacro with-terminal-runtime
  [runtime & body]
  `(call-with-terminal-runtime ~runtime (fn [] ~@body)))

(defn- current-terminal-runtime
  []
  *terminal-runtime*)

(defn- terminal-runtime-state-atom
  []
  (:runtime-state* (current-terminal-runtime)))

(defn- terminal-runtime-state-snapshot
  []
  @(terminal-runtime-state-atom))

(defn- public-terminal-state
  [entry]
  (if entry
    (dissoc entry :generation)
    default-terminal-state))

(defn- ensure-terminal-owner!
  [owner]
  (let [owner-key (terminal-owner-key owner)
        generation* (volatile! nil)]
    (swap! (terminal-runtime-state-atom)
           (fn [{:keys [next-generation] :as runtime-state}]
             (if-let [entry (get-in runtime-state [:owners owner-key])]
               (do
                 (vreset! generation* (:generation entry))
                 runtime-state)
               (let [generation next-generation]
                 (vreset! generation* generation)
                 (-> runtime-state
                     (assoc :next-generation (inc next-generation))
                     (assoc-in [:owners owner-key]
                               (assoc default-terminal-state :generation generation)))))))
    @generation*))

(defn- player-terminal-owner
  [player]
  (let [player-id (or (player-uuid/player-uuid player) (str player))]
    {:client-session-id (or runtime-hooks/*client-session-id*
                            [:terminal-client player-id])
     :screen-id :terminal
     :player-uuid player-id}))

(defn- call-with-terminal-owner
  [owner f]
  (binding [*terminal-owner* owner]
    (f)))

(defmacro ^:private with-terminal-owner
  [owner & body]
  `(call-with-terminal-owner ~owner (fn [] ~@body)))

(defn terminal-state-snapshot
  [owner]
  (public-terminal-state
    (get-in (terminal-runtime-state-snapshot) [:owners (terminal-owner-key owner)])))

(declare swap-terminal-state!)

(defn- reduce-terminal-state-event
  [state event payload]
  (case event
    :terminal/query-response
    (merge state
           {:terminal-installed? (:terminal-installed? payload)
            :installed-apps (set (:installed-apps payload))
            :available-apps (:available-apps payload)})

    :terminal/install-start
    (assoc state :loading? true)

    :terminal/install-result
    (cond-> (assoc state :loading? false)
      (:success payload) (assoc :terminal-installed? true))

    :terminal/install-app-start
    (assoc state :loading? true)

    :terminal/install-app-result
    (cond-> (assoc state :loading? false)
      (:success payload) (update :installed-apps conj (:app-id payload)))

    :terminal/uninstall-app-result
    (cond-> state
      (:success payload) (update :installed-apps disj (:app-id payload)))

    :terminal/set-page
    (assoc state :page (int (or (:page payload) 0)))

    state))

(defn- dispatch-terminal-state-event!
  [owner event payload]
  (swap-terminal-state! owner reduce-terminal-state-event event payload))

(defn- swap-terminal-state!
  [owner f & args]
  (ensure-terminal-owner! owner)
  (let [owner-key (terminal-owner-key owner)]
    (swap! (terminal-runtime-state-atom)
           (fn [runtime-state]
             (apply update-in runtime-state [:owners owner-key] f args)))))

(defn- terminal-owner-active?
  [owner generation]
  (= generation
     (get-in (terminal-runtime-state-snapshot) [:owners (terminal-owner-key owner) :generation])))

(defn clear-terminal-state!
  [owner]
  (let [owner-key (terminal-owner-key owner)]
    (swap! (terminal-runtime-state-atom) update :owners dissoc owner-key))
  nil)

(defn reset-terminal-states-for-test!
  []
  (reset! (terminal-runtime-state-atom) default-terminal-runtime-state)
  nil)

;; ============================================================================
;; Network Communication
;; ============================================================================

(defn query-terminal-state!
  "Query terminal state from server."
  [owner callback]
  (let [generation (ensure-terminal-owner! owner)]
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :get-state)
      {}
      (fn [response]
        (when (terminal-owner-active? owner generation)
          (dispatch-terminal-state-event! owner :terminal/query-response response)
          (when callback (callback response)))))))

(defn install-terminal!
  "Send terminal installation request to server."
  [owner callback]
  (let [generation (ensure-terminal-owner! owner)]
    (dispatch-terminal-state-event! owner :terminal/install-start nil)
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :install-terminal)
      {}
      (fn [response]
        (when (terminal-owner-active? owner generation)
          (dispatch-terminal-state-event! owner :terminal/install-result response)
          (when callback (callback response)))))))

(defn install-app!
  "Send app installation request to server."
  [owner app-id callback]
  (let [generation (ensure-terminal-owner! owner)]
    (dispatch-terminal-state-event! owner :terminal/install-app-start {:app-id app-id})
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :install-app)
      {:app-id (name app-id)}
      (fn [response]
        (when (terminal-owner-active? owner generation)
          (dispatch-terminal-state-event! owner :terminal/install-app-result (assoc response :app-id app-id))
          (when callback (callback response)))))))

(defn uninstall-app!
  "Send app uninstallation request to server."
  [owner app-id callback]
  (let [generation (ensure-terminal-owner! owner)]
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :uninstall-app)
      {:app-id (name app-id)}
      (fn [response]
        (when (terminal-owner-active? owner generation)
          (dispatch-terminal-state-event! owner :terminal/uninstall-app-result (assoc response :app-id app-id))
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
  [owner root-widget]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_appcount")]
    (let [state (terminal-state-snapshot owner)
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
  [owner root-widget]
  (let [loading? (:loading? (terminal-state-snapshot owner))]
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
  (let [owner (player-terminal-owner player)]
    (with-terminal-owner owner
      (try
        ;; Remove old app widgets
        (doseq [i (range 9)]
          (when-let [old-widget (cgui-core/find-widget root-widget (str "app_" i))]
            (cgui-core/remove-widget! root-widget old-widget)))

        ;; Get all apps
        (let [state (terminal-state-snapshot owner)
              all-apps (ordered-apps)
              page (clamp-page all-apps (:page state))
              installed-apps (:installed-apps state)
              _ (dispatch-terminal-state-event! owner :terminal/set-page {:page page})
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
        (update-app-count! owner root-widget)
        (update-loading-indicator! owner root-widget)
        (update-arrow-state! root-widget (ordered-apps) (:page (terminal-state-snapshot owner)))

        (catch Exception e
          (log/error "Error rebuilding app grid:" (ex-message e)))))))

(defn- change-page!
  [delta root-widget player]
  (let [owner (player-terminal-owner player)]
    (with-terminal-owner owner
      (let [apps (ordered-apps)
            current (:page (terminal-state-snapshot owner))
            next-page (clamp-page apps (+ (int (or current 0)) (int delta)))]
        (when (not= next-page current)
          (dispatch-terminal-state-event! owner :terminal/set-page {:page next-page})
          (rebuild-app-grid! root-widget player))))))

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
		(with-terminal-owner owner

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
                                (with-terminal-owner owner
                                  (update-loading-indicator! owner root-widget))))))

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
