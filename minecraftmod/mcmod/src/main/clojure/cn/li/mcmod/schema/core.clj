(ns cn.li.mcmod.schema.core
	"Small Malli helpers for low-frequency boundary contracts.

	Keep this namespace platform-neutral. Callers should compile validators once
	near immutable schemas and call `explain` only on failure."
	(:require [malli.core :as m]))

(defn validator
	"Compile a Malli schema into a reusable predicate function."
	[schema]
	(m/validator schema))

(defn explain
	"Return Malli explanation data for a failed value."
	[schema value]
	(m/explain schema value))

(defn valid?
	"Return true when compiled validator accepts value."
	[compiled-validator value]
	(boolean (compiled-validator value)))

(defn contract-ex-info
	[contract value explain-data]
	(ex-info (str contract " contract violation")
					 {:contract contract
						:value value
						:explain explain-data}))

(defn require-valid
	"Validate value with a compiled validator and throw standardized ex-info on failure."
	[schema compiled-validator contract value]
	(if (valid? compiled-validator value)
		value
		(throw (contract-ex-info contract value (explain schema value)))))