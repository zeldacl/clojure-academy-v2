(ns cn.li.ac.block.developer.block
	(:require [cn.li.mcmod.block.dsl :as bdsl]
						[cn.li.mcmod.block.tile-dsl :as tdsl]
						[cn.li.mcmod.block.tile-logic :as tile-logic]
						[cn.li.mcmod.platform.capability :as platform-cap]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.registry.hooks :as hooks]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.config.modid :as modid]
						[cn.li.ac.block.developer.logic :as dev-logic]
						[cn.li.ac.block.developer.handlers :as dev-handlers]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessReceiver]))

(defonce-guard developer-installed?)

(defn init-developer! []
	(with-init-guard developer-installed?
		(msg-registry/register-block-messages!
			:developer
			[:get-status :start-development :stop-development :list-nodes :connect :disconnect])
		(tdsl/register-tile!
			(tdsl/create-tile-spec
				"developer-normal"
				{:registry-name "developer_normal"
				 :impl :scripted
				 :blocks ["developer-normal" "developer-normal-part"]
				 :tick-fn dev-logic/developer-tick-fn
				 :read-nbt-fn dev-logic/dev-scripted-load-fn
				 :write-nbt-fn dev-logic/dev-scripted-save-fn}))
		(tdsl/register-tile!
			(tdsl/create-tile-spec
				"developer-advanced"
				{:registry-name "developer_advanced"
				 :impl :scripted
				 :blocks ["developer-advanced" "developer-advanced-part"]
				 :tick-fn dev-logic/developer-tick-fn
				 :read-nbt-fn dev-logic/dev-scripted-load-fn
				 :write-nbt-fn dev-logic/dev-scripted-save-fn}))
		(platform-cap/declare-capability! :wireless-receiver IWirelessReceiver
			(fn [be _side] (dev-logic/create-dev-receiver-cap be)))
		(tile-logic/register-tile-capability! "developer-normal" :wireless-receiver)
		(tile-logic/register-tile-capability! "developer-advanced" :wireless-receiver)
		(bdsl/register-block!
			(bdsl/create-block-spec
				"developer-normal"
				{:multi-block {:positions dev-logic/developer-multiblock-positions
											 :rotation-center [0.5 0.0 0.5]
											 :pivot-xz-override [0.0 0.0]
											 :tesr-use-raw-rotation-center? true
											 :tesr-y-deg-override 0.0}
				 :multiblock-mode :controller-parts
				 :controller-block-id "developer-normal"
				 :part-block-id "developer-normal-part"
				 :registry-name "developer_normal"
				 :physical {:material :metal :hardness 4.0 :resistance 10.0 :requires-tool true :harvest-tool :pickaxe :harvest-level 2 :sounds :metal}
				 :rendering {:light-level 1.0 :flat-item-icon? true :textures {:all (modid/asset-path "block" "dev_normal")}}
				 :events {:on-right-click (dev-logic/open-developer-gui-for "developer-normal")}}))
		(bdsl/register-block!
			(bdsl/create-block-spec
				"developer-normal-part"
				{:multiblock-mode :controller-parts
				 :controller-block-id "developer-normal"
				 :part-block-id "developer-normal-part"
				 :registry-name "developer_normal_part"
				 :physical {:material :metal :hardness 4.0 :resistance 10.0 :requires-tool true :harvest-tool :pickaxe :harvest-level 2 :sounds :metal}
				 :rendering {:light-level 1.0 :has-item-form? false :model-parent "minecraft:block/cube_all" :textures {:all (modid/asset-path "block" "dev_normal")}}
				 :events {:on-right-click (dev-logic/open-developer-gui-for "developer-normal")}}))
		(bdsl/register-block!
			(bdsl/create-block-spec
				"developer-advanced"
				{:multi-block {:positions dev-logic/developer-multiblock-positions
											 :rotation-center [0.5 0.0 0.5]
											 :pivot-xz-override [0.0 0.0]
											 :tesr-use-raw-rotation-center? true
											 :tesr-y-deg-override 0.0}
				 :multiblock-mode :controller-parts
				 :controller-block-id "developer-advanced"
				 :part-block-id "developer-advanced-part"
				 :registry-name "developer_advanced"
				 :physical {:material :metal :hardness 5.0 :resistance 12.0 :requires-tool true :harvest-tool :pickaxe :harvest-level 2 :sounds :metal}
				 :rendering {:light-level 2.0 :flat-item-icon? true :textures {:all (modid/asset-path "block" "dev_advanced")}}
				 :events {:on-right-click (dev-logic/open-developer-gui-for "developer-advanced")}}))
		(bdsl/register-block!
			(bdsl/create-block-spec
				"developer-advanced-part"
				{:multiblock-mode :controller-parts
				 :controller-block-id "developer-advanced"
				 :part-block-id "developer-advanced-part"
				 :registry-name "developer_advanced_part"
				 :physical {:material :metal :hardness 5.0 :resistance 12.0 :requires-tool true :harvest-tool :pickaxe :harvest-level 2 :sounds :metal}
				 :rendering {:light-level 2.0 :has-item-form? false :model-parent "minecraft:block/cube_all" :textures {:all (modid/asset-path "block" "dev_advanced")}}
				 :events {:on-right-click (dev-logic/open-developer-gui-for "developer-advanced")}}))
		(hooks/register-network-handler! dev-handlers/register-network-handlers!)
		(hooks/register-client-renderer! 'cn.li.ac.block.developer.render/init!)
		(log/info "Initialized Developer blocks (Normal and Advanced)")))