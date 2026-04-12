(ns cn.li.ac.block.developer.gui
  "Ability Developer TechUI: main status + inventory + wireless receiver tab.

  Loaded with content init; uses `unified-developer-schema` for container atoms/sync.
  GUI close clears tile session (`TileDeveloper` unuse / onGuiClosed)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.ac.block.developer.schema :as dev-schema]
            [cn.li.ac.block.developer.block :as dev-block]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]))

(def ^:private developer-gui-id :developer-gui)

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity tile :player player :container-type :developer}
           (schema-runtime/build-gui-atoms dev-schema/unified-developer-schema state))))

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
                            dev-block/dev-default-state
                            normalize-inventory-2)
  (when-let [tile (:tile-entity container)]
    (try (platform-be/set-changed! tile) (catch Exception _ nil)))
  nil)

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [container player]
  (and (common/still-valid? container player)
       (let [tile (:tile-entity container)
             st (or (common/get-tile-state tile) {})
             pid (str (entity/player-get-uuid player))
             holder (str (:user-uuid st ""))]
         (or (str/blank? holder) (= holder pid)))))

(defn sync-to-client! [container]
  ((schema-runtime/build-sync-to-client-fn dev-schema/unified-developer-schema) container))

(defn get-sync-data [container]
  ((schema-runtime/build-get-sync-data-fn dev-schema/unified-developer-schema) container))

(defn apply-sync-data! [container data]
  ((schema-runtime/build-apply-sync-data-fn dev-schema/unified-developer-schema) container data))

(defn tick! [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((schema-runtime/build-on-close-fn dev-schema/unified-developer-schema) container)
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

(defn- create-developer-main-panel [container]
  (let [w (cgui/create-widget :window {:width gui-width :height gui-height})
        title (cgui/create-widget :text {:text "Ability Developer — use tabs (Inv / Wireless / Developer)" :x 8 :y 6})
        ebar (cgui/create-widget :progress-bar
               {:x 8 :y 88 :width 160 :height 10
                :progress (fn []
                            (let [e (double @(:energy container))
                                  m (max 1.0 (double @(:max-energy container)))]
                              (/ e m)))})
        st-btn (cgui/create-widget :button {:text "Skill tree" :x 8 :y 102 :width 96 :height 14})]
    (events/on-left-click st-btn
      (fn [_]
        (when-let [pl (:player container)]
          (when-let [tile (:tile-entity container)]
            (let [uuid-str (str (entity/player-get-uuid pl))
                  pos (net-helpers/tile-pos-payload tile)
                  bid (platform-be/get-block-id tile)
                  dtype (if (= (name (or bid "")) "developer-advanced") :advanced :normal)]
              (client-bridge/open-skill-tree-screen! uuid-str (merge pos {:developer-type dtype})))))))
    (cgui/add-widget! w title)
    (cgui/add-widget! w ebar)
    (cgui/add-widget! w st-btn)
    w))

(defn create-developer-gui
  [container _player & [opts]]
  (try
    (let [inv-doc (try (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_inv.xml"))
                       (catch Exception _ nil))
          inv-window (if inv-doc
                       (cgui-doc/get-widget inv-doc "main")
                       (cgui/create-widget :window {:width gui-width :height gui-height}))
          dev-window (create-developer-main-panel container)
          wireless-window (wireless-tab/create-wireless-panel {:mode :receiver :container container})
          pages [{:id "inv" :window inv-window}
                 {:id "wireless" :window wireless-window}
                 {:id "developer" :window dev-window}]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          main-widget (:window tech-ui)
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
      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (tech-ui/reset-info-area! info-area)
      (cgui/add-widget! main-widget info-area)
      (log/info "Created Ability Developer TechUI")
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
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
      (tech-ui/assoc-tech-ui-screen-size base))))

(defn- developer-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (= :developer (:container-type container))))

(defonce ^:private developer-gui-installed? (atom false))

(defn init-developer-gui!
  []
  (when (compare-and-set! developer-gui-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id developer-gui-id
       :slots [{:id :inv-0 :type :generic :x 42 :y 72}
               {:id :inv-1 :type :generic :x 42 :y 92}]})
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "developer"
        {:gui-id 13
         :display-name "Ability Developer"
         :gui-type :developer
         :registry-name "developer_gui"
         :screen-factory-fn-kw :create-developer-screen
         :slot-layout (slot-schema/get-slot-layout developer-gui-id)
         :container-predicate developer-container?
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
    (log/info "Ability Developer GUI registered (gui-id 13)")))
