(ns cn.li.ac.block.cat-engine.block
	"Cat Engine block - thin coordinator."
	(:require [cn.li.mcmod.block.dsl :as bdsl]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.block.tile-dsl :as tdsl]
						[cn.li.mcmod.block.tile-logic :as tile-logic]
						[cn.li.mcmod.platform.capability :as platform-cap]
						[cn.li.ac.registry.hooks :as hooks]
						[cn.li.mcmod.util.log :as log]
						[cn.li.ac.config.modid :as modid]
						[cn.li.ac.block.cat-engine.logic :as cat-logic]
						[cn.li.ac.block.cat-engine.capability :as cat-cap])
	(:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard cat-engine-installed?)

(defn init-cat-engine!
	[]
	(with-init-guard cat-engine-installed?
		(tdsl/register-tile!
			(tdsl/create-tile-spec
				"cat-engine"
				{:registry-name "cat_engine"
				 :impl :scripted
				 :blocks ["cat-engine"]
				 :tick-fn cat-logic/cat-tick-fn
				 :read-nbt-fn cat-logic/cat-scripted-load-fn
				 :write-nbt-fn cat-logic/cat-scripted-save-fn}))
		(platform-cap/declare-capability! :cat-engine-generator IWirelessGenerator
			(fn [be _side] (cat-cap/->CatEngineGeneratorImpl be)))
		(tile-logic/register-tile-capability! "cat-engine" :cat-engine-generator)
		(bdsl/register-block!
			(bdsl/create-block-spec
				"cat-engine"
				{:registry-name "cat_engine"
				 :physical {:material :metal
										:hardness 2.0
										:resistance 6.0
										:requires-tool true
										:harvest-tool :pickaxe
										:harvest-level 1
										:sounds :metal}
				 :rendering {:model-parent "minecraft:block/cube_all"
										 :textures {:all (modid/asset-path "block" "cat_engine")}
										 :flat-item-icon? true
										 :light-level 0}
				 :events {:on-right-click cat-logic/cat-right-click!}}))
		(hooks/register-client-renderer! 'cn.li.ac.block.cat-engine.render/init!)
		(log/info "Initialized Cat Engine block")))