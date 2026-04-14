(ns cn.li.ac.integration.block.energy-converter.gui
	(:require [cn.li.ac.gui.platform-adapter :as gui]
						[cn.li.ac.gui.tech-ui-common :as tech-ui]
						[cn.li.ac.wireless.gui.container.common :as common]
						[cn.li.ac.wireless.gui.tab :as wireless-tab]
						[cn.li.mcmod.gui.cgui :as cgui]
						[cn.li.mcmod.gui.dsl :as gui-dsl]
						[cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
						[cn.li.mcmod.gui.slot-schema :as slot-schema]
						[cn.li.mcmod.gui.slot-registry :as slot-registry]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.util.log :as log]
						[cn.li.ac.integration.block.energy-converter.config :as ec-config]))

(def ^:private converter-slot-schema-id :energy-converter)
(def ^:private converter-gui-type :energy-converter)

(defn- output-block?
	[block-id]
	(ec-config/output-block? block-id))

(defn- block-wireless-mode
	[block-id]
	(if (output-block? block-id) :generator :receiver))

(defn- create-container
	[tile player]
	(let [state (or (common/get-tile-state tile) {})
				block-id (str (platform-be/get-block-id tile))]
		{:tile-entity tile
		 :player player
		 :container-type converter-gui-type
		 :block-id block-id
		 :wireless-mode (atom (block-wireless-mode block-id))
		 :energy (atom (double (get state :energy 0.0)))
		 :max-energy (atom (double (get state :max-energy ec-config/energy-capacity)))
		 :wireless-enabled (atom (boolean (get state :wireless-enabled true)))
		 :status (atom (if (output-block? block-id) "OUTPUT" "INPUT"))}))

(defn- get-slot-count [_container]
	(slot-registry/get-slot-count converter-slot-schema-id))

(defn- get-slot-item [_container _slot-index] nil)
(defn- set-slot-item! [_container _slot-index _item-stack] nil)
(defn- slot-changed! [_container _slot-index] nil)
(defn- can-place-item? [_container _slot-index _item-stack] false)
(defn- still-valid? [_container _player] true)

(defn- sync-to-client!
	[container]
	(let [state (or (common/get-tile-state (:tile-entity container)) {})
				energy (double (get state :energy 0.0))
				max-energy (double (get state :max-energy ec-config/energy-capacity))
				block-id (str (:block-id container))
				mode (block-wireless-mode block-id)]
		(reset! (:energy container) energy)
		(reset! (:max-energy container) max-energy)
		(reset! (:wireless-enabled container) (boolean (get state :wireless-enabled true)))
		(reset! (:wireless-mode container) mode)
		(reset! (:status container) (if (= mode :generator) "OUTPUT" "INPUT"))))

(defn- get-sync-data
	[container]
	{:energy @(:energy container)
	 :max-energy @(:max-energy container)
	 :wireless-enabled @(:wireless-enabled container)
	 :status @(:status container)
	 :wireless-mode @(:wireless-mode container)})

(defn- apply-sync-data!
	[container data]
	(reset! (:energy container) (double (:energy data 0.0)))
	(reset! (:max-energy container) (double (:max-energy data ec-config/energy-capacity)))
	(reset! (:wireless-enabled container) (boolean (:wireless-enabled data true)))
	(reset! (:status container) (str (:status data "INPUT")))
	(reset! (:wireless-mode container) (keyword (:wireless-mode data :receiver))))

(defn- tick!
	[container]
	(sync-to-client! container))

(defn- on-close [_container] nil)
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- create-wireless-page
	[container]
	(wireless-tab/create-wireless-panel
		{:mode (if (= @(:wireless-mode container) :generator) :generator :receiver)
		 :container container}))

(defn- create-screen
	[container minecraft-container _player]
	(sync-to-client! container)
	(let [inv-page (tech-ui/create-inventory-page "inventory")
				wireless-page {:id "wireless" :window (create-wireless-page container)}
				pages [inv-page wireless-page]
				container-id (gui/get-menu-container-id minecraft-container)
				tech (apply tech-ui/create-tech-ui pages)
				_ (tabbed-gui/attach-tab-sync! pages tech container container-id)
				root (:window tech)
				info-area (tech-ui/create-info-area)
				max-e (fn [] (max 1.0 (double @(:max-energy container))))
				ratio (fn [] (/ (double @(:energy container)) (max-e)))
				mode-s (fn [] (if (= @(:wireless-mode container) :generator) "GEN" "RECV"))
				enabled-s (fn [] (if @(:wireless-enabled container) "ON" "OFF"))
				y0 (tech-ui/add-histogram info-area
						 [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
						 0)
				y1 (tech-ui/add-sepline info-area "Converter" y0)
				y2 (tech-ui/add-property info-area "block" (fn [] (str (:block-id container))) y1)
				y3 (tech-ui/add-property info-area "status" (fn [] @(:status container)) y2)
				y4 (tech-ui/add-property info-area "wireless" enabled-s y3)
				_y5 (tech-ui/add-property info-area "load" (fn [] (format "%.1f%%" (* 100.0 (ratio)))) y4)
				_y6 (tech-ui/add-property info-area "mode" mode-s _y5)
				_ (cgui/set-position! info-area (+ (cgui/get-width (:window inv-page)) 7) 5)
				_ (tech-ui/reset-info-area! info-area)
				_ (cgui/add-widget! root info-area)
				base (cgui/create-cgui-screen-container root minecraft-container)]
		(tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current tech)))))

(defn- converter-container?
	[container]
	(and (map? container)
			 (= (:container-type container) converter-gui-type)
			 (contains? container :tile-entity)
			 (contains? container :energy)))

(defonce ^:private converter-gui-installed? (atom false))

(defn register-converter-guis!
	[]
	(when (compare-and-set! converter-gui-installed? false true)
		(slot-schema/register-slot-schema!
			{:schema-id converter-slot-schema-id
			 :slots []})
		(gui-dsl/register-gui!
			(gui-dsl/create-gui-spec
				"energy-converter"
				{:gui-id 14
				 :display-name "Energy Converter"
				 :gui-type converter-gui-type
				 :registry-name "energy_converter_gui"
				 :screen-factory-fn-kw :create-energy-converter-screen
				 :slot-layout (slot-schema/get-slot-layout converter-slot-schema-id)
				 :container-predicate converter-container?
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
		(log/info "Energy Converter GUI registered (gui-id 14)")))