(ns cn.li.ac.block.imag-fusor.gui
  "CLIENT-ONLY: Imaginary Fusor GUI"
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.ac.block.imag-fusor.config :as cfg]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]))

(def fusor-gui-type :imag-fusor)
(def fusor-slot-schema-id :imag-fusor)

(defn- phase-liquid-unit?
  [stack]
  (and stack
       (= (recipes/item-id-from-stack stack) cfg/matter-unit-item-id)
       (= (int (try (pitem/item-get-damage stack) (catch Exception _ -1)))
          cfg/matter-unit-phase-liquid-meta)))

(defn create-container
  [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity tile
            :player player
            :container-type fusor-gui-type}
           (schema-runtime/build-gui-atoms fusor-schema/imag-fusor-schema state))))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count fusor-slot-schema-id))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn slot-changed! [_container _slot-index] nil)

(defn can-place-item?
  [_container slot-index item-stack]
  (let [slot-index (int slot-index)]
    (case slot-index
      0 (boolean (recipes/find-recipe item-stack))
      2 (phase-liquid-unit? item-stack)
      3 (energy/is-energy-item-supported? item-stack)
      false)))

(defn still-valid? [_container _player] true)

(defn sync-to-client! [container]
  ((schema-runtime/build-sync-to-client-fn fusor-schema/imag-fusor-schema) container))

(defn get-sync-data [container]
  ((schema-runtime/build-get-sync-data-fn fusor-schema/imag-fusor-schema) container))

(defn apply-sync-data! [container data]
  ((schema-runtime/build-apply-sync-data-fn fusor-schema/imag-fusor-schema) container data))

(defn tick! [container]
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((schema-runtime/build-on-close-fn fusor-schema/imag-fusor-schema) container))

(defn- bind-progress!
  [inv-window container]
  (when-let [widget (cgui/find-widget inv-window "progress")]
    (when-let [bar (comp/get-component widget :progressbar)]
      (events/on-frame widget
        (fn [_]
          (comp/set-progress! bar (double (or @(:work-progress container) 0.0))))))))

(defn- bind-requirement-text!
  [inv-window container]
  (when-let [widget (cgui/find-widget inv-window "text_imagneeded")]
    (when-let [tb (comp/get-textbox-component widget)]
      (events/on-frame widget
        (fn [_]
          (let [req (int (or @(:current-recipe-liquid container) 0))]
            (comp/set-text! tb (if (pos? req) (str req) "IDLE"))))))))

(defn create-screen
  [container minecraft-container _player]
  (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_imagfusor.xml"))
        inv-window (cgui-doc/get-widget doc "main")
        wireless-window (wireless-tab/create-wireless-panel {:mode :receiver :container container})
        inv-page {:id "inv" :window inv-window}
        wireless-page {:id "wireless" :window wireless-window}
        pages [inv-page wireless-page]
        container-id (gui/get-menu-container-id minecraft-container)
        tech (apply tech-ui/create-tech-ui pages)
        _ (tabbed-gui/attach-tab-sync! pages tech container container-id)
        root (:window tech)
        info-area (tech-ui/create-info-area)
        max-e (fn [] (max 1.0 (double (or @(:max-energy container) cfg/max-energy))))
        max-liquid (fn [] (max 1.0 (double (or @(:tank-size container) cfg/tank-size))))
        _ (bind-progress! inv-window container)
        _ (bind-requirement-text! inv-window container)
        _ (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
        _ (tech-ui/reset-info-area! info-area)
        y0 (tech-ui/add-histogram
             info-area
             [(tech-ui/hist-buffer (fn [] (double (or @(:energy container) 0.0)) ) max-e)
              {:label "Liquid"
               :color 0xFF8A7DFF
               :value-fn (fn [] (/ (double (or @(:liquid-amount container) 0)) (max-liquid)))
               :desc-fn (fn [] (str (int (or @(:liquid-amount container) 0)) " mB"))}]
             0)
        y1 (tech-ui/add-sepline info-area "Imag Fusor" y0)
        _y2 (tech-ui/add-property info-area "required_liquid"
                                  (fn []
                                    (let [req (int (or @(:current-recipe-liquid container) 0))]
                                      (if (pos? req) (str req) "IDLE")))
                                  y1)]
    (cgui/add-widget! root info-area)
    (tech-ui/assoc-tech-ui-screen-size
      (assoc (cgui/create-cgui-screen-container root minecraft-container)
             :current-tab-atom (:current tech)))))

(defn- fusor-container?
  [container]
  (and (map? container)
       (= (:container-type container) fusor-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :work-progress)))

(defonce ^:private imag-fusor-gui-installed? (atom false))

(defn init-imag-fusor-gui!
  []
  (when (compare-and-set! imag-fusor-gui-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id fusor-slot-schema-id
       :slots [{:id :input :type :input :x 13 :y 49}
               {:id :output :type :output :x 143 :y 49}
               {:id :phase-input :type :phase-input :x 13 :y 10}
               {:id :energy :type :energy :x 42 :y 80}
               {:id :phase-output :type :phase-output :x 143 :y 10}]})
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "imag-fusor"
        {:gui-id 5
         :display-name "Imag Fusor"
         :gui-type fusor-gui-type
         :registry-name "imag_fusor_gui"
         :screen-factory-fn-kw :create-imag-fusor-screen
         :slot-layout (slot-schema/get-slot-layout fusor-slot-schema-id)
         :container-predicate fusor-container?
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
    (log/info "Imaginary Fusor GUI initialized")))
