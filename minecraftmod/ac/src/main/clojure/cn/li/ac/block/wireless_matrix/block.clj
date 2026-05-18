(ns cn.li.ac.block.wireless-matrix.block
	"Wireless Matrix block - thin coordinator."
	(:require [cn.li.mcmod.block.dsl :as bdsl]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.block.tile-dsl :as tdsl]
						[cn.li.mcmod.block.tile-logic :as tile-logic]
						[cn.li.mcmod.platform.capability :as platform-cap]
						[cn.li.ac.registry.hooks :as hooks]
						[cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
						[cn.li.ac.block.wireless-matrix.handlers :as matrix-handlers])
	(:import [cn.li.acapi.wireless IWirelessMatrix]))

(defonce-guard wireless-matrix-installed?)

(defn init-wireless-matrix!
	[]
	(with-init-guard wireless-matrix-installed?
		(matrix-logic/ensure-matrix-slot-schema!)
		(tile-logic/register-tile-kind!
			:wireless-matrix
			{:tick-fn matrix-logic/matrix-scripted-tick-fn
			 :read-nbt-fn matrix-logic/matrix-scripted-load-fn
			 :write-nbt-fn matrix-logic/matrix-scripted-save-fn})
		(tdsl/register-tile!
			(tdsl/create-tile-spec
				"wireless-matrix"
				{:registry-name "matrix"
				 :impl :scripted
				 :blocks ["wireless-matrix" "wireless-matrix-part"]
				 :tile-kind :wireless-matrix}))
		(platform-cap/declare-capability! :wireless-matrix IWirelessMatrix
			(fn [be _side] (matrix-logic/->WirelessMatrixImpl be)))
		(tile-logic/register-tile-capability! "wireless-matrix" :wireless-matrix)
		(tile-logic/register-container! "wireless-matrix" matrix-logic/matrix-container-fns)
		(bdsl/defmultiblock 'wireless-matrix
			:multi-block {:positions [[0 0 0] [0 0 1] [1 0 1] [1 0 0]
																[0 1 0] [0 1 1] [1 1 1] [1 1 0]]
										:rotation-center [1.0 0 1.0]}
			:common {:physical {:material :stone
													:hardness 3.0
													:resistance 6.0
													:requires-tool true
													:harvest-tool :pickaxe
													:harvest-level 1
													:sounds :stone}
							 :rendering {:light-level 1.0}}
			:controller {:registry-name "matrix"
									 :rendering {:flat-item-icon? true}
									 :events {:on-right-click (matrix-logic/handle-matrix-right-click)
														:on-place (matrix-logic/handle-matrix-place)
														:on-break (matrix-logic/handle-matrix-break)}}
			:part {:registry-name "matrix_part"
						 :rendering {:model-parent "minecraft:block/block"}
						 :events {:on-right-click (matrix-logic/handle-matrix-right-click)}})
		(hooks/register-network-handler! matrix-handlers/register-network-handlers!)
		(hooks/register-client-renderer! 'cn.li.ac.block.wireless-matrix.render/init!)))