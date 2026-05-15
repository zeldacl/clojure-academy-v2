(remove-ns 'cn.li.forge1201.registry.state)

(ns cn.li.forge1201.registry.state
	"Centralized Forge registry runtime state.

	Keeps mutable registry atoms and lookup helpers out of the mod entry namespace
	so mod orchestration stays focused on bootstrapping/event wiring."
	(:require [cn.li.mcmod.protocol.metadata :as registry-metadata])
	(:import [net.minecraftforge.registries RegistryObject]))

;; Storage for registered objects populated during mod initialization.
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-entities (atom {}))
(defonce registered-block-entities (atom {}))
(defonce registered-fluid-types (atom {}))
(defonce registered-fluids-source (atom {}))
(defonce registered-fluids-flowing (atom {}))
(defonce registered-sounds (atom {}))
(defonce registered-effects (atom {}))
(defonce registered-particles (atom {}))

(defn- safe-registry-object-get
	[^RegistryObject registered-obj]
	(when (and registered-obj (.isPresent registered-obj))
		(.get registered-obj)))

(defn get-registered-entity-type
	"Get a registered EntityType by entity-id."
	[entity-id]
	(when-let [registered-obj (get @registered-entities entity-id)]
		(safe-registry-object-get ^RegistryObject registered-obj)))

(defn get-registered-block-entity-type
	"Get a registered BlockEntityType by tile-id or block-id."
	[tile-or-block-id]
	(let [tile-id (or (when (contains? @registered-block-entities tile-or-block-id)
											tile-or-block-id)
										(registry-metadata/get-block-tile-id tile-or-block-id))]
		(when-let [registered-obj (and tile-id (get @registered-block-entities tile-id))]
			(safe-registry-object-get ^RegistryObject registered-obj))))

(defn get-registered-block
	"Get a registered block by its DSL id."
	[block-id]
	(when-let [registered-obj (get @registered-blocks block-id)]
		(safe-registry-object-get ^RegistryObject registered-obj)))

(defn get-registered-item
	"Get a registered item by its DSL id."
	[item-id]
	(when-let [registered-obj (get @registered-items item-id)]
		(safe-registry-object-get ^RegistryObject registered-obj)))

(defn get-registered-block-item
	"Get a registered block item by block DSL id."
	[block-id]
	(when-let [registered-obj (get @registered-items (str block-id "-item"))]
		(safe-registry-object-get ^RegistryObject registered-obj)))

(defn get-registered-fluid-source
	[fluid-id]
	(when-let [registered-obj (get @registered-fluids-source fluid-id)]
		(safe-registry-object-get ^RegistryObject registered-obj)))

(defn get-registered-fluid-flowing
	[fluid-id]
	(when-let [registered-obj (get @registered-fluids-flowing fluid-id)]
		(safe-registry-object-get ^RegistryObject registered-obj)))