(ns cn.li.ac.integration.block.energy-converter.gui
	(:require [cn.li.ac.gui.manifest :as gui-manifest]
	          [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.block.gui.sync :as gui-sync]
						[cn.li.ac.gui.tech-ui-common :as tech-ui]
						[cn.li.ac.wireless.gui.container.common :as common]
						[cn.li.ac.wireless.gui.tab :as wireless-tab]
						[cn.li.mcmod.gui.spec :as gui-reg]
						[cn.li.mcmod.gui.slot-schema :as slot-schema]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.util.log :as log]
						[cn.li.ac.integration.block.energy-converter.config :as ec-config]
						[cn.li.ac.integration.block.energy-converter.schema :as ec-schema]))

(def ^:private converter-slot-schema-id :energy-converter)
(def ^:private converter-gui-type :energy-converter)

(defn- output-block?
	[block-id]
	(ec-config/output-block? block-id))

(defn- block-wireless-mode
	[block-id]
	(if (output-block? block-id) :generator :receiver))

(defn- reset-derived-mode!
	[container]
	(let [mode (block-wireless-mode (:block-id container))]
		(reset! (:wireless-mode container) mode)
		(reset! (:status container) (if (= mode :generator) "OUTPUT" "INPUT"))))

(defn- apply-derived-mode!
	[container data]
	(let [mode (keyword (or (:wireless-mode data) (block-wireless-mode (:block-id container))))]
		(reset! (:wireless-mode container) mode)
		(reset! (:status container) (str (:status data (if (= mode :generator) "OUTPUT" "INPUT"))))))

(def ^:private converter-sync
	(gui-sync/schema-sync-fns ec-schema/energy-converter-gui-schema
		{:after-sync! reset-derived-mode!}))

(defn- create-container
	[tile player]
	(let [state (or (common/get-tile-state tile) {})
				block-id (str (platform-be/get-block-id tile))]
		(gui-sync/create-schema-container ec-schema/energy-converter-gui-schema
			 tile
			 player
			 converter-gui-type
			 {:gui-id (gui-manifest/gui-id :energy-converter)
				:state state
				:base {:block-id block-id
						 :wireless-mode (atom (block-wireless-mode block-id))
						 :status (atom (if (output-block? block-id) "OUTPUT" "INPUT"))}})))

(defn- get-slot-count [_container]
	(slot-schema/tile-slot-count converter-slot-schema-id))

(defn- get-slot-item [_container _slot-index] nil)
(defn- set-slot-item! [_container _slot-index _item-stack] nil)
(defn- slot-changed! [_container _slot-index] nil)
(defn- can-place-item? [_container _slot-index _item-stack] false)
(defn- still-valid? [_container _player] true)

(def ^:private server-menu-sync! (:server-menu-sync! converter-sync))

(def ^:private on-close (:on-close converter-sync))
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- create-wireless-page
	[container & [opts]]
	(wireless-tab/create-wireless-panel
		{:role (if (= @(:wireless-mode container) :generator) :generator :receiver)
		 :container container
		 :menu (:menu opts)}))

(defn- create-screen
	[container minecraft-container _player]
	(let [inv-page (tech-ui/create-inventory-page "inventory")
				wireless-page {:id "wireless" :window (create-wireless-page container {:menu minecraft-container})}
				pages [inv-page wireless-page]
				max-e (fn [] (max 1.0 (double @(:max-energy container))))
				ratio (fn [] (/ (double @(:energy container)) (max-e)))
				mode-s (fn [] (if (= @(:wireless-mode container) :generator) "GEN" "RECV"))
				enabled-s (fn [] (if @(:wireless-enabled container) "ON" "OFF"))]
		(tech-ui/create-tech-screen-container
			{:pages pages
			 :container container
			 :minecraft-container minecraft-container
			 :build-info-area!
			 (fn [info-area]
				 (let [y0 (tech-ui/add-histogram info-area
													 [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
													 0)
						 y1 (tech-ui/add-sepline info-area "Converter" y0)
						 y2 (tech-ui/add-property info-area "block" (fn [] (str (:block-id container))) y1)
						 y3 (tech-ui/add-property info-area "status" (fn [] @(:status container)) y2)
						 y4 (tech-ui/add-property info-area "wireless" enabled-s y3)
						 y5 (tech-ui/add-property info-area "load" (fn [] (format "%.1f%%" (* 100.0 (ratio)))) y4)]
					 (tech-ui/add-property info-area "mode" mode-s y5)))})))

(defn- converter-container?
	[container]
	(and (map? container)
			 (= (:container-type container) converter-gui-type)
			 (contains? container :tile-entity)
			 (contains? container :energy)))

(defonce-guard converter-gui-installed?)

(defn register-converter-guis!
	[]
	(with-init-guard converter-gui-installed?
		(slot-schema/register-slot-schema!
			{:schema-id converter-slot-schema-id
			 :slots []})
		(gui-reg/register-block-gui!
			(gui-manifest/gui-name :energy-converter)
			(merge (gui-manifest/gui-registration :energy-converter)
					 {:container-predicate converter-container?
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
		(log/info "Energy Converter GUI registered (gui-id 14)")))