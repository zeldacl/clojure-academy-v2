(ns cn.li.ac.block.imag-fusor.gui
  "CLIENT-ONLY: Imaginary Fusor GUI"
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.imag-fusor.config :as cfg]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]))

(def fusor-gui-type :imag-fusor)
(def fusor-slot-schema-id :imag-fusor)
(def ^:private fusor-sync
  (gui-sync/schema-sync-fns fusor-schema/imag-fusor-schema))

(defn- phase-liquid-unit?
  [stack]
  (and stack
       (= (recipes/item-id-from-stack stack) cfg/matter-unit-item-id)
       (= (int (try (pitem/item-get-damage stack) (catch Exception _ -1)))
          cfg/matter-unit-phase-liquid-meta)))

(defn create-container
  [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container fusor-schema/imag-fusor-schema
                                      tile
                                      player
                                      fusor-gui-type
                                      {:gui-id (gui-manifest/gui-id :imag-fusor)
                                       :state state})))

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count fusor-slot-schema-id))

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

(def server-menu-sync! (:server-menu-sync! fusor-sync))

(defn handle-button-click! [_container _button-id _player] nil)

(def on-close (:on-close fusor-sync))

(defn- bind-progress!
  [inv-window container]
  (when-let [widget (cgui-core/find-widget inv-window "progress")]
    (when-let [bar (comp/get-component widget :progressbar)]
      (events/on-frame widget
        (fn [_]
          (comp/set-progress! bar (double (or @(:work-progress container) 0.0))))))))

(defn- bind-requirement-text!
  [inv-window container]
  (when-let [widget (cgui-core/find-widget inv-window "text_imagneeded")]
    (when-let [tb (comp/get-textbox-component widget)]
      (events/on-frame widget
        (fn [_]
          (let [req (int (or @(:current-recipe-liquid container) 0))]
            (comp/set-text! tb (if (pos? req) (str req) "IDLE"))))))))

(defn create-screen
  [container minecraft-container _player]
  (let [inv-page (tech-ui/create-rework-page "guis/rework/page_imagfusor.xml")
        inv-window (:window inv-page)
      pages [inv-page]
        max-e (fn [] (max 1.0 (double (or @(:max-energy container) cfg/max-energy))))
        max-liquid (fn [] (max 1.0 (double (or @(:tank-size container) cfg/tank-size))))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :bind! (fn [_]
                (bind-progress! inv-window container)
                (bind-requirement-text! inv-window container))
       :build-info-area!
       (fn [info-area]
         (let [y0 (tech-ui/add-histogram
                    info-area
                    [(tech-ui/hist-buffer (fn [] (double (or @(:energy container) 0.0))) max-e)
                     {:label "Liquid"
                      :color 0xFF8A7DFF
                      :value-fn (fn [] (/ (double (or @(:liquid-amount container) 0)) (max-liquid)))
                      :desc-fn (fn [] (str (int (or @(:liquid-amount container) 0)) " mB"))}]
                    0)
               y1 (tech-ui/add-sepline info-area "Imag Fusor" y0)]
           (tech-ui/add-property info-area "required_liquid"
                                  (fn []
                                    (let [req (int (or @(:current-recipe-liquid container) 0))]
                                      (if (pos? req) (str req) "IDLE")))
                                  y1)))})))

(defn- fusor-container?
  [container]
  (and (map? container)
       (= (:container-type container) fusor-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :work-progress)))

(defonce-guard imag-fusor-gui-installed?)

(defn init-imag-fusor-gui!
  []
  (with-init-guard imag-fusor-gui-installed?
    (slot-schema/register-slot-schema!
      {:schema-id fusor-slot-schema-id
       :slots [{:id :input :type :input :x 13 :y 49}
               {:id :output :type :output :x 143 :y 49}
               {:id :phase-input :type :phase-input :x 13 :y 10}
               {:id :energy :type :energy :x 42 :y 80}
               {:id :phase-output :type :phase-output :x 143 :y 10}]})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :imag-fusor)
      (merge (gui-manifest/gui-registration :imag-fusor)
             {:container-predicate fusor-container?
       :container-fn create-container
       :screen-fn create-screen
       :server-menu-sync-fn server-menu-sync!
       :validate-fn still-valid?
       :close-fn on-close
       :button-click-fn handle-button-click!
       :slot-count-fn get-slot-count
       :slot-get-fn get-slot-item
       :slot-set-fn set-slot-item!
       :slot-can-place-fn can-place-item?
        :slot-changed-fn slot-changed!}))
    (log/info "Imaginary Fusor GUI initialized")))
