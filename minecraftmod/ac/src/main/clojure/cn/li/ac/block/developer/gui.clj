(ns cn.li.ac.block.developer.gui
  "Ability Developer TechUI: classic `page_developer.xml` + embedded wireless list in `parent_right/area` + Inv + wireless tab (list fetch lazy until Wireless tab is opened).

  Loaded with content init; uses `unified-developer-schema` for container atoms/sync.
  GUI close clears tile session (`TileDeveloper` unuse / onGuiClosed)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.developer.schema :as dev-schema]
            [cn.li.ac.block.developer.logic :as dev-logic]
            [cn.li.ac.block.developer.panel :as dev-panel]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]))

(def ^:private developer-gui-id :developer-gui)

;; Classic `page_developer.xml` is 400×187; add gutter + InfoArea min width (matches `tech_ui_common` default).
(def ^:private developer-tech-main-w (+ 400.0 7.0 100.0))
(def ^:private developer-tech-main-h (double tech-ui/gui-height))

;; Vanilla player-inventory menu image width ≈176; extend to fit TechUI main (see `screen_impl` containerTick).
(def ^:private developer-forge-image-dx 331)

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)
(def ^:private developer-sync
  (gui-sync/schema-sync-fns dev-schema/unified-developer-schema))

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container dev-schema/unified-developer-schema tile player :developer {:state state})))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count developer-gui-id))

(defn- normalize-inventory-2
  [state]
  (let [v (vec (take 2 (concat (vec (:inventory state [])) (repeat nil))))]
    (assoc state :inventory v)))

(defn can-place-item? [_container _slot-index _item-stack]
  true)

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack
                            dev-logic/dev-default-state
                            normalize-inventory-2)
  (when-let [tile (:tile-entity container)]
    (try (platform-be/set-changed! tile) (catch Exception _ nil)))
  nil)

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [container player]
  (and (common/still-valid? container player)
       (let [tile (:tile-entity container)
             st (or (common/get-tile-state tile) {})
         pid (uuid/player-uuid player)
             holder (str (:user-uuid st ""))]
         (or (str/blank? holder) (= holder pid)))))

(def sync-to-client! (:sync-to-client! developer-sync))
(def get-sync-data (:get-sync-data developer-sync))
(def apply-sync-data! (:apply-sync-data! developer-sync))

(defn tick! [container]
  (gui-sync/sync-tick! container sync-to-client! {:ticker-key :sync-ticker}))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((:on-close developer-sync) container)
  (when-let [tile (:tile-entity container)]
    (when-let [pl (:player container)]
      (let [lvl (entity/player-get-level pl)]
        (when (and lvl (not (world/world-is-client-side* lvl)))
          (try
            (let [st (or (platform-be/get-custom-state tile) {})]
              (platform-be/set-custom-state! tile
                (assoc st :user-uuid "" :user-name "" :is-developing false))
              (platform-be/set-changed! tile))
            (catch Exception e
              (log/debug "Developer on-close tile update:" (ex-message e)))))))))

(defn create-developer-gui
  [container _player & [opts]]
  (try
    (let [inv-page (tech-ui/create-inventory-page "phasegen")
          node-icon (modid/asset-path "textures" "guis/icons/icon_node.png")
          wireless-window (wireless-tab/create-wireless-panel
                {:role :receiver
                 :container container
                 :tab-logo-path node-icon
                 :connected-row-logo-path node-icon
                 :defer-initial-rebuild? true})
          activate-wireless-tab! (wireless-tab/developer-wireless-tab-lazy-activator
                                   wireless-window container node-icon)
          classic-root (dev-panel/load-classic-developer-page)
          _ (when-let [area (cgui-core/find-widget classic-root "parent_right/area")]
              (wireless-tab/create-embedded-developer-wireless-panel!
                container area {:connected-row-logo-path node-icon}))
          pages [inv-page
                 {:id "developer" :window classic-root}
                 {:id "wireless" :window wireless-window}]
          container-id (when-let [m (:menu opts)] (container-state/get-menu-container-id m))
          ;; Do not use `apply` here: `(apply create-tech-ui [p1 p2 p3] opts-map)` treats the
          ;; map as a seq of MapEntries and breaks page maps (nil :id / :window).
          tech-ui (tech-ui/create-tech-ui pages
                    {:main-size [developer-tech-main-w developer-tech-main-h]})
          ;; `page_inv` / `page_wireless` root uses XML CENTER align; wide `:main-size` would
          ;; center 176px-wide pages in `tech_ui_main` (~165px right shift vs matrix).
          _ (doseq [w [(:window inv-page) wireless-window]]
              (cgui-core/set-w-align! w :left)
              (cgui-core/set-h-align! w :top))
          _ (when-let [cur-atom (:current tech-ui)]
              (add-watch cur-atom :developer-wireless-tab-lazy
                (fn [_ _ _old new-id]
                  (when (= "wireless" new-id)
                    (activate-wireless-tab!)))))
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          main-widget (:window tech-ui)
          _ (dev-panel/attach-classic-developer-bindings! classic-root container
              {:switch-wireless-tab! #(tabbed-gui/switch-tab! tech-ui pages "wireless")})
          info-area (tech-ui/create-info-area)
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          y0 (tech-ui/add-histogram
               info-area
               [(tech-ui/hist-buffer (fn [] (double @(:energy container))) (max-e))]
               0)
          y1 (tech-ui/add-sepline info-area "Developer" y0)
          y2 (tech-ui/add-property info-area "tier" (fn [] (str @(:tier container))) y1)
          y3 (tech-ui/add-property info-area "structure_ok" (fn [] (str @(:structure-valid container))) y2)
          _y4 (tech-ui/add-property info-area "developing" (fn [] (str @(:is-developing container))) y3)]
      (cgui-core/set-position! info-area (+ (cgui-core/get-width main-widget) 7) 5)
      (tech-ui/reset-info-area! info-area)
      (cgui-core/add-widget! main-widget info-area)
      (log/info "Created Ability Developer TechUI (classic panel + phasegen inv)")
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Developer GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  [container minecraft-container player]
  (let [gui (create-developer-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui-screen/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (-> base
          (tech-ui/assoc-tech-ui-screen-size)
          (assoc :size-dx developer-forge-image-dx
                 :current-tab-atom (:current gui)))
      (-> base
          (tech-ui/assoc-tech-ui-screen-size)
          (assoc :size-dx developer-forge-image-dx)))))

(defn- developer-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (= :developer (:container-type container))))

(def ^:private developer-gui-guard-lock
  (Object.))

(def ^:private ^:dynamic *developer-gui-installed?*
  false)

(defn init-developer-gui!
  []
  (when-not (var-get #'*developer-gui-installed?*)
    (locking developer-gui-guard-lock
      (when-not (var-get #'*developer-gui-installed?*)
        (slot-schema/register-slot-schema!
          {:schema-id developer-gui-id
           ;; AcademyCraft 1.0.7: `TileDeveloper` inv size 2; `ContainerNode`-style slots on `ui_phasegen`.
           :slots [{:id :inv-0 :type :generic :x 42 :y 10}
                   {:id :inv-1 :type :generic :x 42 :y 80}]})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :developer)
      (merge (gui-manifest/gui-registration :developer)
             {:container-predicate developer-container?
       :container-fn create-container
       :screen-fn create-screen
       :tick-fn tick!
       :sync-get get-sync-data
       :sync-apply apply-sync-data!
       :validate-fn still-valid?
       :close-fn on-close
       :button-click-fn handle-button-click!
       :slot-count-fn get-slot-count
       :slot-get-fn get-slot-item
       :slot-set-fn set-slot-item!
       :slot-can-place-fn can-place-item?
        :slot-changed-fn slot-changed!}))
        (alter-var-root #'*developer-gui-installed?* (constantly true))
        (log/info "Ability Developer GUI registered (gui-id 13)")))))
