(ns cn.li.mcmod.block.dsl
	"Public aggregate entrypoint for the block DSL.
   
	 The actual implementation now lives in:
	 - cn.li.mcmod.block.dsl-properties
	 - cn.li.mcmod.block.dsl-multiblock
	 - cn.li.mcmod.block.dsl-templates
	 - cn.li.mcmod.block.dsl-validators
	 - cn.li.mcmod.block.dsl-core
	 - cn.li.mcmod.block.query
   
	 This namespace is intentionally retained as the user-facing DSL surface;
	 implementation details remain in the focused modules above."
	(:require [cn.li.mcmod.block.dsl-properties :as props]
						[cn.li.mcmod.block.dsl-multiblock :as mb]
						[cn.li.mcmod.block.dsl-templates :as templates]
						[cn.li.mcmod.block.dsl-validators :as validators]
						[cn.li.mcmod.block.dsl-core :as core]
						[cn.li.mcmod.block.query :as q]))

(defonce materials props/materials)
(defonce sound-types props/sound-types)
(defonce tool-types props/tool-types)
(defonce default-hardness props/default-hardness)
(defonce default-resistance props/default-resistance)
(defonce default-light-level props/default-light-level)
(defonce default-friction props/default-friction)
(defonce default-creative-tab props/default-creative-tab)

(defonce block-registry core/block-registry)

(defonce get-block-spec q/get-block-spec)
(defonce list-blocks q/list-all-blocks)
(defonce controller-parts-block? q/controller-parts-block?)
(defonce is-controller-block? q/is-controller-block?)
(defonce is-part-block? q/is-part-block?)
(defonce is-multi-block? q/is-multi-block?)
(defonce has-tile-entity? q/has-tile-entity?)
(defonce is-light-emitter? q/is-light-emitter?)
(defonce get-controller-block-id q/get-controller-block-id)
(defonce get-part-block-id q/get-part-block-id)
(defonce get-tile-kind q/get-tile-kind)
(defonce get-light-level q/get-light-level)
(defonce get-hardness q/get-hardness)
(defonce get-material q/get-material)
(defonce get-structure-offsets q/get-structure-offsets)
(defonce has-block-state-properties? q/has-block-state-properties?)
(defonce has-block-entity? q/has-block-entity?)

(defonce calculate-multi-block-positions mb/calculate-multi-block-positions)
(defonce normalize-positions mb/normalize-positions)
(defonce validate-multi-block-positions mb/validate-multi-block-positions)
(defonce get-multi-block-master-pos mb/get-multi-block-master-pos)
(defonce resolve-multi-block-master-pos mb/resolve-multi-block-master-pos)
(defonce all-multi-block-positions mb/all-multi-block-positions)
(defonce can-place-multi-block? mb/can-place-multi-block?)
(defonce is-multi-block-complete? mb/is-multi-block-complete?)
(defonce create-cross-shape mb/create-cross-shape)
(defonce create-l-shape mb/create-l-shape)
(defonce create-t-shape mb/create-t-shape)
(defonce create-pyramid-shape mb/create-pyramid-shape)
(defonce create-hollow-cube mb/create-hollow-cube)

(defonce create-block-spec core/create-block-spec)
(defonce register-block! core/register-block!)
(defonce get-block core/get-block)
(defonce get-block-properties core/get-block-properties)

(defonce ore-template templates/ore-template)
(defonce wood-template templates/wood-template)
(defonce metal-template templates/metal-template)
(defonce glass-template templates/glass-template)
(defonce light-block-template templates/light-block-template)
(defonce multi-block-template templates/multi-block-template)
(defonce irregular-multi-block-template templates/irregular-multi-block-template)
(defonce merge-templates templates/merge-templates)

(def validate-block-spec validators/validate-block-spec)

(defmacro defblock [block-name & options]
	`(core/defblock ~block-name ~@options))

(defmacro defmultiblock [base-name & options]
	`(core/defmultiblock ~base-name ~@options))

(defmacro defcontroller-multiblock [base-name & options]
	`(core/defcontroller-multiblock ~base-name ~@options))

(defmacro defblock-template [block-name config]
	`(core/defblock-template ~block-name ~config))