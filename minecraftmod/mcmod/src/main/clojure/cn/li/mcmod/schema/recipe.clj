(ns cn.li.mcmod.schema.recipe
	"Malli contracts for low-frequency datagen recipe and achievement metadata."
	(:require [clojure.string :as str]
						[cn.li.mcmod.schema.core :as schema-core]))

(def ^:private resource-id-regex
	#"^[a-z0-9._-]+:[a-z0-9._/-]+$")

(def ^:private non-blank-string-schema
	[:and string? [:fn (fn [v] (not (str/blank? v)))]] )

(def ingredient-schema
	[:or
	 [:map [:item [:re resource-id-regex]]]
	 [:map [:tag [:re resource-id-regex]]]])

(def result-schema
	[:map
	 [:item [:re resource-id-regex]]
	 [:count {:optional true} [:and int? [:>= 1]]]])

(def shaped-recipe-schema
	[:map
	 [:id non-blank-string-schema]
	 [:type [:= :shaped]]
	 [:pattern [:sequential [:and string? [:fn (fn [s] (pos? (count s)))]]]]
	 [:key [:map-of char? ingredient-schema]]
	 [:result result-schema]])

(def shapeless-recipe-schema
	[:map
	 [:id non-blank-string-schema]
	 [:type [:= :shapeless]]
	 [:ingredients [:sequential ingredient-schema]]
	 [:result result-schema]])

(def smelting-recipe-schema
	[:map
	 [:id non-blank-string-schema]
	 [:type [:= :smelting]]
	 [:ingredient ingredient-schema]
	 [:result result-schema]
	 [:experience [:and number? [:>= 0.0]]]
	 [:cooking-time [:and int? [:>= 1]]]])

(def recipe-schema
	[:or shaped-recipe-schema shapeless-recipe-schema smelting-recipe-schema])

(def recipes-schema
	[:sequential recipe-schema])

(def achievement-tab-schema
	[:map
	 [:id keyword?]
	 [:background [:re resource-id-regex]]])

(def achievement-tabs-schema
	[:sequential achievement-tab-schema])

(def criterion-schema
	[:or
	 [:map [:type [:= :custom]] [:criterion-id non-blank-string-schema]]
	 [:map [:type [:= :inventory-changed]] [:items [:sequential [:re resource-id-regex]]]]])

(def achievement-schema
	[:map
	 [:id non-blank-string-schema]
	 [:tab keyword?]
	 [:criteria [:sequential criterion-schema]]
	 [:translation [:map
									[:en_us [:map-of string? string?]]
									[:zh_cn [:map-of string? string?]]]]
	 [:icon {:optional true} [:re resource-id-regex]]
	[:parent {:optional true} [:or nil? non-blank-string-schema]]
	 [:frame {:optional true} [:enum :task :goal :challenge]]
	[:trigger-key {:optional true} [:or nil? map?]]
	 [:hidden? {:optional true} boolean?]])

(def achievements-schema
	[:sequential achievement-schema])

(def translations-schema
	[:map
	 [:en_us [:map-of string? string?]]
	 [:zh_cn [:map-of string? string?]]])

(def ^:private recipes-validator
	(schema-core/validator recipes-schema))

(def ^:private achievement-tabs-validator
	(schema-core/validator achievement-tabs-schema))

(def ^:private achievements-validator
	(schema-core/validator achievements-schema))

(def ^:private translations-validator
	(schema-core/validator translations-schema))

(defn require-recipes!
	[contract value]
	(schema-core/require-valid recipes-schema recipes-validator contract value))

(defn require-achievement-tabs!
	[contract value]
	(schema-core/require-valid achievement-tabs-schema achievement-tabs-validator contract value))

(defn require-achievements!
	[contract value]
	(schema-core/require-valid achievements-schema achievements-validator contract value))

(defn require-translations!
	[contract value]
	(schema-core/require-valid translations-schema translations-validator contract value))