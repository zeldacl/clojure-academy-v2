(ns cn.li.ac.block.platform-bridge
	"AC blockstate datagen bindings for mcmod blockstate bridge."
	(:require [cn.li.mcmod.block.blockstate-definition :as mcmod-blockstate]
						[cn.li.ac.block.blockstate-definition :as ac-blockstate]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))

(defn install-blockstate-hooks!
	[]
	(when (compare-and-set! hooks-installed? false true)
		(mcmod-blockstate/register-blockstate-hooks!
			{:get-all-definitions ac-blockstate/get-all-definitions
			 :get-block-state-definition ac-blockstate/get-block-state-definition
			 :is-multipart-block? ac-blockstate/is-multipart-block?
			 :get-model-cube-texture-config ac-blockstate/get-model-cube-texture-config
			 :get-model-texture-config ac-blockstate/get-model-texture-config
			 :get-item-model-id ac-blockstate/get-item-model-id})
		(log/info "AC blockstate hooks installed"))
	nil)