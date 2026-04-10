(ns cn.li.mcmod.platform.be
	"Platform-neutral utilities for interacting with ScriptedBlockEntity.

	ac code should use these functions instead of calling Java interop directly.
	Platform implementations must bind *be-capability-slot-fn* during init so
	that get-capability-slot can retrieve Forge Capability objects by key."
	(:require [cn.li.mcmod.util.log :as log]
						[cn.li.mcmod.platform.world :as world]
						[cn.li.mcmod.platform.capability :as cap]))

(defn- call-log
	[level & xs]
	(case level
		:error (apply log/error xs)
		:warn (apply log/warn xs)
		:info (apply log/info xs)
		nil))

(defn- world-get-tile-entity*
	[w block-pos]
	(world/world-get-tile-entity* w block-pos))

(defn- capability-present?
	[lo]
	(cap/is-present? lo))

(defn- capability-or-else
	[lo default]
	(cap/or-else lo default))

(defn- capability-get
	[provider capability side]
	(cap/get-capability provider capability side))

(defprotocol IBlockEntity
	"Protocol for BlockEntity operations. Platform implementations must extend
	this protocol on the platform BlockEntity class to perform Java interop.
	Core code should call these protocol functions instead of raw interop."
	(be-get-level [this])
	(be-get-world [this])
	(be-get-custom-state [this])
	(be-set-custom-state! [this state])
	(be-get-block-id [this])
	(be-set-changed! [this]))

(def ^:dynamic *be-capability-slot-fn* nil)
(def ^:dynamic *be-get-level-fn* nil)
(def ^:dynamic *be-get-world-fn* nil)
(def ^:dynamic *be-get-custom-state-fn* nil)
(def ^:dynamic *be-set-custom-state-fn* nil)
(def ^:dynamic *be-get-block-id-fn* nil)
(def ^:dynamic *be-set-changed-fn* nil)

(defn be-get-world-safe [be]
	(or (try (if *be-get-level-fn* (*be-get-level-fn* be) (be-get-level be))
					 (catch Exception _ nil))
			(try (if *be-get-world-fn* (*be-get-world-fn* be) (be-get-world be))
					 (catch Exception _ nil))))

(defn get-block-entity [w block-pos]
	(try
		(world-get-tile-entity* w block-pos)
		(catch Exception e
			(call-log :warn "get-block-entity failed:" (ex-message e))
			nil)))

(defn get-custom-state [be]
	(when be
		(try
			(if *be-get-custom-state-fn*
				(*be-get-custom-state-fn* be)
				(be-get-custom-state be))
			(catch Exception e
				(call-log :warn "get-custom-state failed:" (ex-message e))
				nil))))

(defn set-custom-state! [be state]
	(when be
		(try
			(if *be-set-custom-state-fn*
				(*be-set-custom-state-fn* be state)
				(be-set-custom-state! be state))
			(if *be-set-changed-fn*
				(*be-set-changed-fn* be)
				(be-set-changed! be))
			(catch Exception e
				(call-log :error "set-custom-state! failed:" (ex-message e))))))

(defn get-block-id [be]
	(when be
		(try
			(if *be-get-block-id-fn*
				(*be-get-block-id-fn* be)
				(be-get-block-id be))
			(catch Exception e
				(call-log :warn "get-block-id failed:" (ex-message e))
				nil))))

(defn set-changed! [be]
	(when be
		(try
			(if *be-set-changed-fn*
				(*be-set-changed-fn* be)
				(be-set-changed! be))
			(catch Exception e
				(call-log :warn "set-changed! failed:" (ex-message e))
				nil))))

(defn get-capability-slot [key-string]
	(when *be-capability-slot-fn*
		(try
			(*be-capability-slot-fn* key-string)
			(catch Exception _ nil))))

(defn get-capability [be key-string]
	(when (and be *be-capability-slot-fn*)
		(try
			(let [capability (*be-capability-slot-fn* key-string)]
				(when capability
					(let [lo (capability-get be capability nil)]
						(when (and lo (capability-present? lo))
							(capability-or-else lo nil)))))
			(catch Exception _ nil))))