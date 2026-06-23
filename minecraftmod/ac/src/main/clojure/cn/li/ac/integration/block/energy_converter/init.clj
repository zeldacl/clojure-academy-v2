(ns cn.li.ac.integration.block.energy-converter.init
	"Energy converter integration bootstrap.

	Provides content-loader entry points used by integration content init."
	(:require [cn.li.mcmod.block.dsl :as bdsl]
	          [cn.li.mcmod.block.tile-dsl :as tdsl]
	          [cn.li.ac.config.modid :as modid]
	          [cn.li.mcmod.block.tile-logic :as tile-logic]
		          [cn.li.mcmod.platform.be :as platform-be]
		          [cn.li.mcmod.platform.capability :as platform-cap]
		          [cn.li.ac.integration.block.energy-converter.base :as ec-base]
	          [cn.li.ac.integration.block.energy-converter.config :as ec-config]
		          [cn.li.ac.integration.block.energy-converter.schema :as ec-schema]
	          [cn.li.ac.integration.block.energy-converter.eu-input :as eu-input]
	          [cn.li.ac.integration.block.energy-converter.eu-output :as eu-output]
	          [cn.li.ac.integration.block.energy-converter.rf-input :as rf-input]
	          [cn.li.ac.integration.block.energy-converter.rf-output :as rf-output]
		          [cn.li.ac.block.energy-converter.wireless-impl :as ec-wireless]
	          [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.integration.block.energy-converter.platform-bridge :as ec-bridge]
						[cn.li.mcmod.util.log :as log])
		(:import [cn.li.acapi.wireless IWirelessGenerator IWirelessReceiver]))

(def ^:private converter-definitions
	[{:block-id rf-input/block-id
	  :registry-name rf-input/registry-name
	  :display-name rf-input/display-name
	  :texture rf-input/texture}
	 {:block-id rf-output/block-id
	  :registry-name rf-output/registry-name
	  :display-name rf-output/display-name
	  :texture rf-output/texture}
	 {:block-id eu-input/block-id
	  :registry-name eu-input/registry-name
	  :display-name eu-input/display-name
	  :texture eu-input/texture}
	 {:block-id eu-output/block-id
	  :registry-name eu-output/registry-name
	  :display-name eu-output/display-name
	  :texture eu-output/texture}])

(defn- open-converter-gui!
	[{:keys [player world pos sneaking] :as _ctx}]
	(when-not sneaking
		(try
			(if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.gui.open/open-gui-by-type)]
				(open-gui-by-type player :energy-converter world pos)
				(do (log/error "Energy Converter GUI open fn not found: cn.li.ac.gui.open/open-gui-by-type") nil))
			(catch Exception e
				(log/error "Failed to open Energy Converter GUI:" (ex-message e))
				nil))))

(defn- register-converter-block!
	[{:keys [block-id registry-name texture]}]
	(tdsl/register-tile!
		(tdsl/create-tile-spec
			block-id
			{:registry-name registry-name
			 :impl :scripted
			 :blocks [block-id]
			 :read-nbt-fn ec-base/read-nbt-fn
			 :write-nbt-fn ec-base/write-nbt-fn}))
	(bdsl/register-block!
		(bdsl/create-block-spec
			block-id
			{:registry-name registry-name
			 :physical {:material :stone
									:hardness 2.5
									:requires-tool true
									:harvest-tool :pickaxe
									:harvest-level 0
									:sounds :stone}
			 :rendering {:model-parent "minecraft:block/cube_all"
									:textures {:all (modid/asset-path "block" texture)}
									:flat-item-icon? true}
			 :events {:on-right-click open-converter-gui!}})))

(defonce-guard converters-loaded?)
(defonce-guard converters-initialized?)

(defn load-converters!
	[]
	(with-init-guard converters-loaded?
		(ec-bridge/install-energy-integration-hooks!)
		(log/info "Energy converters loaded"
							{:count (count ec-config/supported-blocks)})))

(defn init-converters!
	[]
	(with-init-guard converters-initialized?
		(doseq [converter converter-definitions]
			(register-converter-block! converter))
		;; --- register wireless capabilities (all registrations are idempotent) ---
		(platform-cap/declare-capability!
			:wireless-generator IWirelessGenerator
			(fn [be _side]
				(ec-wireless/create-wireless-generator
					be
					(fn [] (or (platform-be/get-custom-state be) (ec-schema/default-state-map)))
					(fn [s] (platform-be/set-custom-state! be s)))))
		(platform-cap/declare-capability!
			:wireless-receiver IWirelessReceiver
			(fn [be _side]
				(ec-wireless/create-wireless-receiver
					be
					(fn [] (or (platform-be/get-custom-state be) (ec-schema/default-state-map)))
					(fn [s] (platform-be/set-custom-state! be s))
					{:max-energy (fn [] (double (ec-config/energy-capacity)))
					 :bandwidth (fn [] (double (ec-config/transfer-bandwidth)))})))
		;; receivers: rf-input, eu-input   |  generators: rf-output, eu-output
		(doseq [tile-id ["rf-input" "eu-input"]]
			(tile-logic/register-tile-capability! tile-id :wireless-receiver))
		(doseq [tile-id ["rf-output" "eu-output"]]
			(tile-logic/register-tile-capability! tile-id :wireless-generator))
		(log/info "Energy converters initialized"
							{:count (count ec-config/supported-blocks)})))