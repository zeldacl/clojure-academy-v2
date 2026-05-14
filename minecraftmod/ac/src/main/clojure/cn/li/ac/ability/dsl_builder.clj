(ns cn.li.ac.ability.dsl-builder
	"Explicit DSL builder entrypoint for ability declarations.

	This namespace mirrors `cn.li.ac.ability.dsl` but makes the Phase C split
	visible in the public architecture."
	(:require [cn.li.ac.ability.definition-core :as definition-core]
						[cn.li.ac.ability.service.registry :as registry]))

(defmacro defcategory
	[sym & opts]
	(let [category-spec (definition-core/build-category-spec sym (apply hash-map opts))
				id (:id category-spec)
				rest-map (dissoc category-spec :id)]
		`(let [category-map# (assoc ~rest-map :id ~id :ac/content-type :category)]
			 (def ~sym category-map#))))

(defmacro defskill
	[sym & opts]
	(let [skill-map (definition-core/build-skill-spec sym (apply hash-map opts))]
		`(let [skill-map# (assoc ~skill-map :ac/content-type :skill)]
			 (def ~sym skill-map#))))

(defmacro defskill!
	[sym & opts]
	(let [skill-map (definition-core/build-skill-spec sym (apply hash-map opts))]
		`(let [skill-map# ~skill-map
					 registered# (registry/register-skill! skill-map#)]
			 (def ~sym registered#))))