(ns cn.li.ac.block.wind-gen.gui
  "CLIENT-ONLY: Wind Generator GUI (main + base)."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.util.log :as log]))

(def ^:private main-schema-id :wind-gen-main)
(def ^:private base-schema-id :wind-gen-base)

(defn- fan-item-stack? [stack]
  (when (and stack (not (item/item-is-empty? stack)))
    (let [rn (try (some-> stack item/item-get-item item/item-get-registry-name) (catch Exception _ nil))
          s (str rn)]
      (or (= rn "windgen_fan") (= rn "my_mod:windgen_fan") (.endsWith s ":windgen_fan")))))

(defn- create-main-container [tile player]
  {:tile-entity tile :player player :container-type :wind-gen-main
   :status (atom "INCOMPLETE") :complete (atom false)
   :no-obstacle (atom false) :fan-installed (atom false)})

(defn- main-slot-count [_] (slot-registry/get-slot-count main-schema-id))
(defn- main-get-slot [container slot] (common/get-slot-item-be container slot))
(defn- main-set-slot! [container slot stack]
  (common/set-slot-item-be! container slot stack {:inventory [nil]} identity))
(defn- main-can-place? [_ _slot stack] (boolean (fan-item-stack? stack)))
(defn- main-still-valid? [_ _player] true)

(defn- main-sync! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})]
    (reset! (:status container) (str (get state :status "INCOMPLETE")))
    (reset! (:complete container) (boolean (get state :complete false)))
    (reset! (:no-obstacle container) (boolean (get state :no-obstacle false)))
    (reset! (:fan-installed container) (boolean (get state :fan-installed false)))))

(defn- create-main-screen [container minecraft-container player]
  (main-sync! container)
  (let [inv-page (tech-ui/create-inventory-page "windmain")
        pages [inv-page]
        container-id (gui/get-menu-container-id minecraft-container)
        tech (apply tech-ui/create-tech-ui pages)
        _ (tabbed-gui/attach-tab-sync! pages tech container container-id)
        root (:window tech)
        info-area (tech-ui/create-info-area)
        y0 (tech-ui/add-sepline info-area "Info" 0)
        y1 (tech-ui/add-property info-area "fan" (fn [] (if @(:fan-installed container) "YES" "NO")) y0)
        _y2 (tech-ui/add-property info-area "obstacle" (fn [] (if @(:no-obstacle container) "CLEAR" "BLOCKED")) y1)
        _ (cgui/set-position! info-area (+ (cgui/get-width (:window inv-page)) 7) 5)
        _ (cgui/add-widget! root info-area)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current tech)))))

(defn- main-container? [container] (= (:container-type container) :wind-gen-main))

(defn- create-base-container [tile player]
  {:tile-entity tile :player player :container-type :wind-gen-base
   :energy (atom 0.0) :max-energy (atom 20000.0)
   :gen-speed (atom 0.0) :status (atom "BASE_ONLY")})

(defn- base-slot-count [_] (slot-registry/get-slot-count base-schema-id))
(defn- base-get-slot [container slot] (common/get-slot-item-be container slot))
(defn- base-set-slot! [container slot stack]
  (common/set-slot-item-be! container slot stack {:inventory [nil]} identity))
(defn- base-can-place? [_ _slot stack] (boolean (energy/is-energy-item-supported? stack)))
(defn- base-still-valid? [_ _player] true)

(defn- base-sync! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})]
    (reset! (:energy container) (double (get state :energy 0.0)))
    (reset! (:max-energy container) (double (get state :max-energy 20000.0)))
    (reset! (:gen-speed container) (double (get state :gen-speed 0.0)))
    (reset! (:status container) (str (get state :status "BASE_ONLY")))))

(defn- create-base-screen [container minecraft-container player]
  (base-sync! container)
  (let [doc (cgui-doc/read-xml "assets/my_mod/guis/rework/page_windbase.xml")
        inv-window (cgui-doc/get-widget doc "main")
        wireless-window (wireless-tab/create-wireless-panel {:mode :generator :container container})
        pages [{:id "inv" :window inv-window} {:id "wireless" :window wireless-window}]
        container-id (gui/get-menu-container-id minecraft-container)
        tech (apply tech-ui/create-tech-ui pages)
        _ (tabbed-gui/attach-tab-sync! pages tech container container-id)
        root (:window tech)
        info-area (tech-ui/create-info-area)
        max-e (fn [] (max 1.0 (double @(:max-energy container))))
        y0 (tech-ui/add-histogram info-area [(tech-ui/hist-buffer (fn [] (double @(:energy container))) (max-e))] 0)
        y1 (tech-ui/add-sepline info-area "Info" y0)
        y2 (tech-ui/add-property info-area "gen_speed" (fn [] (format "%.2fIF/T" (double @(:gen-speed container)))) y1)
        _y3 (tech-ui/add-property info-area "status" (fn [] @(:status container)) y2)
        _ (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
        _ (cgui/add-widget! root info-area)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current tech)))))

(defn- base-container? [container] (= (:container-type container) :wind-gen-base))

(defonce ^:private wind-gui-installed? (atom false))

(defn init-wind-gen-gui!
  []
  (when (compare-and-set! wind-gui-installed? false true)
    (slot-schema/register-slot-schema! {:schema-id main-schema-id :slots [{:id :fan :type :standard :x 78 :y 9}]})
    (slot-schema/register-slot-schema! {:schema-id base-schema-id :slots [{:id :energy :type :energy :x 42 :y 80}]})

    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "wind-gen-main"
        {:gui-id 3 :display-name "Wind Generator Main" :gui-type :wind-gen-main
         :registry-name "wind_gen_main_gui" :screen-factory-fn-kw :create-wind-main-screen
         :slot-layout (slot-schema/get-slot-layout main-schema-id)
         :container-predicate main-container? :container-fn create-main-container
         :screen-fn create-main-screen :tick-fn main-sync!
         :sync-get (fn [c] {:status @(:status c) :complete @(:complete c) :no-obstacle @(:no-obstacle c) :fan-installed @(:fan-installed c)})
         :sync-apply (fn [c d] (reset! (:status c) (str (:status d "INCOMPLETE")))
                                (reset! (:complete c) (boolean (:complete d false)))
                                (reset! (:no-obstacle c) (boolean (:no-obstacle d false)))
                                (reset! (:fan-installed c) (boolean (:fan-installed d false))))
         :validate-fn main-still-valid?
         :slot-count-fn main-slot-count :slot-get-fn main-get-slot :slot-set-fn main-set-slot!
         :slot-can-place-fn main-can-place? :slot-changed-fn (fn [_ _] nil)}))

    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "wind-gen-base"
        {:gui-id 4 :display-name "Wind Generator Base" :gui-type :wind-gen-base
         :registry-name "wind_gen_base_gui" :screen-factory-fn-kw :create-wind-base-screen
         :slot-layout (slot-schema/get-slot-layout base-schema-id)
         :container-predicate base-container? :container-fn create-base-container
         :screen-fn create-base-screen :tick-fn base-sync!
         :sync-get (fn [c] {:energy @(:energy c) :max-energy @(:max-energy c) :gen-speed @(:gen-speed c) :status @(:status c)})
         :sync-apply (fn [c d] (reset! (:energy c) (double (:energy d 0.0)))
                                (reset! (:max-energy c) (double (:max-energy d 20000.0)))
                                (reset! (:gen-speed c) (double (:gen-speed d 0.0)))
                                (reset! (:status c) (str (:status d "BASE_ONLY"))))
         :validate-fn base-still-valid?
         :slot-count-fn base-slot-count :slot-get-fn base-get-slot :slot-set-fn base-set-slot!
         :slot-can-place-fn base-can-place? :slot-changed-fn (fn [_ _] nil)}))

    (log/info "Wind Generator GUIs initialized (main/base)")))