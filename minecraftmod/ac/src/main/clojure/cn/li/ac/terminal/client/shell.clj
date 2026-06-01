(ns cn.li.ac.terminal.client.shell
  "CLIENT-ONLY: terminal shell GUI, RPC, and app grid."
  (:require [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.terminal.client.apps :as client-apps]
            [cn.li.ac.terminal.client.runtime :as runtime]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard terminal-ui-hooks-installed?)

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
  [index]
  (let [row (quot index (:columns grid-config))
        col (rem index (:columns grid-config))]
    [(get (:col-x grid-config) col)
     (get (:row-y grid-config) row)]))

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

(defn query-terminal-state!
  [owner callback]
  (let [generation (runtime/ensure-owner! owner)]
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :get-state)
      {}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/query-response response)
          (when callback (callback response)))))))

(defn install-terminal!
  [owner callback]
  (let [generation (runtime/ensure-owner! owner)]
    (runtime/dispatch-event! owner :terminal/install-start nil)
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :install-terminal)
      {}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/install-result response)
          (when callback (callback response)))))))

(defn install-app!
  [owner app-id callback]
  (let [generation (runtime/ensure-owner! owner)]
    (runtime/dispatch-event! owner :terminal/install-app-start {:app-id app-id})
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :install-app)
      {:app-id (name app-id)}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/install-app-result (assoc response :app-id app-id))
          (when callback (callback response)))))))

(defn uninstall-app!
  [owner app-id callback]
  (let [generation (runtime/ensure-owner! owner)]
    (net-client/send-to-server
      owner
      (terminal-messages/msg-id :uninstall-app)
      {:app-id (name app-id)}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/uninstall-app-result (assoc response :app-id app-id))
          (when callback (callback response)))))))

(defn- player-owner
  [player]
  (runtime/player-owner (or (player-uuid/player-uuid player) (str player))))

(defn- create-app-widget
  [app index installed? on-click]
  (let [[x y] (grid-position index)
        widget (cgui-core/create-widget :pos [x y] :size [(:app-width grid-config) (:app-height grid-config)])
        bg (cgui-core/create-widget :pos [0 0] :size [151 151])
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        icon (cgui-core/create-widget :pos [9 32] :size [110 110])
        icon-texture (or (:icon app) (modid/asset-path "textures" "guis/apps/default/icon.png"))
        _ (comp/add-component! icon (comp/draw-texture icon-texture [255 255 255 (if installed? 255 160)]))
        text (cgui-core/create-widget :pos [0 148] :size [151 21])
        _ (comp/add-component! text (comp/text-box :text (:name app) :color 0xFFFFFFFF :scale 1.0))
        _ (events/on-left-click widget (fn [_] (on-click app installed?)))]
    (cgui-core/add-widget! widget bg)
    (cgui-core/add-widget! widget icon)
    (cgui-core/add-widget! widget text)
    widget))

(defn- update-app-count!
  [owner root-widget]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_appcount")]
    (let [state (runtime/state-snapshot owner)
          installed-count (count (:installed-apps state))
          total-count (count (:available-apps state))
          apps (catalog/ordered-apps)
          page (clamp-page apps (:page state))
          total-pages (page-count apps)
          text (str installed-count "/" total-count
                    " Applications  P"
                    (inc page)
                    "/"
                    total-pages)]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb text)))))

(defn- update-username!
  [root-widget player]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_username")]
    (when-let [tb (comp/get-textbox-component text-widget)]
      (comp/set-text! tb (entity/player-get-name player)))))

(defn- update-loading-indicator!
  [owner root-widget]
  (let [loading? (:loading? (runtime/state-snapshot owner))]
    (when-let [icon-widget (cgui-core/find-widget root-widget "icon_loading")]
      (cgui-core/set-visible! icon-widget loading?))
    (when-let [text-widget (cgui-core/find-widget root-widget "text_loading")]
      (cgui-core/set-visible! text-widget loading?))))

(declare rebuild-app-grid!)

(defn- handle-app-click
  [app installed? player root-widget]
  (let [owner (player-owner player)
        app-id (:id app)]
    (if installed?
      (client-apps/launch! app-id player)
      (install-app! owner app-id
                    (fn [response]
                      (when (:success response)
                        (rebuild-app-grid! root-widget player))
                      (when-not (:success response)
                        (log/error "Failed to install app:" app-id)))))))

(defn rebuild-app-grid!
  [root-widget player]
  (let [owner (player-owner player)]
    (runtime/with-owner owner
      (try
        (doseq [i (range 9)]
          (when-let [old-widget (cgui-core/find-widget root-widget (str "app_" i))]
            (cgui-core/remove-widget! root-widget old-widget)))
        (let [state (runtime/state-snapshot owner)
              all-apps (catalog/ordered-apps)
              page (clamp-page all-apps (:page state))
              installed-apps (:installed-apps state)
              _ (runtime/dispatch-event! owner :terminal/set-page {:page page})
              offset (* page apps-per-page)
              page-apps (->> all-apps (drop offset) (take apps-per-page))]
          (doseq [[index app] (map-indexed vector page-apps)]
            (let [installed? (contains? installed-apps (:id app))
                  app-widget (create-app-widget app index installed?
                                                (fn [a inst?]
                                                  (handle-app-click a inst? player root-widget)))]
              (cgui-core/set-name! app-widget (str "app_" index))
              (cgui-core/add-widget! root-widget app-widget))))
        (update-app-count! owner root-widget)
        (update-loading-indicator! owner root-widget)
        (update-arrow-state! root-widget (catalog/ordered-apps) (:page (runtime/state-snapshot owner)))
        (catch Exception e
          (log/error "Error rebuilding app grid:" (ex-message e)))))))

(defn- change-page!
  [delta root-widget player]
  (let [owner (player-owner player)]
    (runtime/with-owner owner
      (let [apps (catalog/ordered-apps)
            current (:page (runtime/state-snapshot owner))
            next-page (clamp-page apps (+ (int (or current 0)) (int delta)))]
        (when (not= next-page current)
          (runtime/dispatch-event! owner :terminal/set-page {:page next-page})
          (rebuild-app-grid! root-widget player))))))

(defn- bind-navigation-controls!
  [root-widget player]
  (when-let [up (cgui-core/find-widget root-widget "arrow_up")]
    (events/unlisten! up :left-click)
    (events/on-left-click up (fn [_] (change-page! -1 root-widget player))))
  (when-let [down (cgui-core/find-widget root-widget "arrow_down")]
    (events/unlisten! down :left-click)
    (events/on-left-click down (fn [_] (change-page! 1 root-widget player)))))

(defn create-terminal-gui
  [player]
  (try
    (log/info "Creating terminal GUI for player:" (entity/player-get-name player))
    (let [owner (player-owner player)
          root-widget (cgui-doc/read-xml (modid/asset-path "guis" "terminal.xml"))]
      (runtime/with-owner owner
        (if-not root-widget
          (cgui-core/create-widget :size [640 785])
          (do
            (update-username! root-widget player)
            (bind-navigation-controls! root-widget player)
            (query-terminal-state! owner
                                   (fn [_]
                                     (rebuild-app-grid! root-widget player)))
            (when-let [template (cgui-core/find-widget root-widget "app_template")]
              (cgui-core/set-visible! template false))
            (events/on-frame root-widget
                             (let [counter (atom 0)]
                               (fn [_]
                                 (swap! counter inc)
                                 (when (zero? (mod @counter 40))
                                   (runtime/with-owner owner
                                     (update-loading-indicator! owner root-widget))))))
            (log/info "Terminal GUI created successfully")
            root-widget))))
    (catch Exception e
      (log/error "Error creating terminal GUI:" (ex-message e))
      (cgui-core/create-widget :size [640 785]))))

(defn open-terminal
  [player]
  (log/info "Opening terminal for player:" (entity/player-get-name player))
  (try
    (let [owner (player-owner player)]
      (query-terminal-state! owner
                             (fn [response]
                               (if (:terminal-installed? response)
                                 (client-bridge/open-screen! :ac/terminal {:player player})
                                 (do
                                   (log/info "Terminal not installed, installing...")
                                   (install-terminal! owner
                                                      (fn [install-response]
                                                        (when (:success install-response)
                                                          (client-bridge/open-screen! :ac/terminal {:player player}))
                                                        (when-not (:success install-response)
                                                          (log/error "Failed to install terminal")))))))))
    (catch Exception e
      (log/error "Error opening terminal:" (ex-message e)))))

(defn install-ui-hooks!
  []
  (with-init-guard terminal-ui-hooks-installed?
    (platform-ui/register-widget-factory!
      :ac/terminal-gui
      (fn [{:keys [player]}]
        (create-terminal-gui player)))
    (log/info "AC terminal UI hooks installed"))
  nil)
