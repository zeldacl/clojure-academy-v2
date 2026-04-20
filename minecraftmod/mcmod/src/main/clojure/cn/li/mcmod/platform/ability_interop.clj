(ns cn.li.mcmod.platform.ability-interop
	"Compatibility shim for legacy ability interop namespace.

	Canonical protocol now lives in cn.li.mcmod.platform.runtime-interop."
	(:require [cn.li.mcmod.platform.runtime-interop :as runtime-interop]))

(def ^:dynamic *ability-interop*
	"Legacy dynamic var kept for compatibility.

	Platform should bind runtime-interop/*runtime-interop* as canonical value;
	this var may still be set for older callers."
	nil)

(defn get-player-view
	[interop player-uuid]
	(let [impl (or interop runtime-interop/*runtime-interop* *ability-interop*)]
		(when impl
			(runtime-interop/get-player-view impl player-uuid))))

(defn get-player-main-hand-item
	[interop player-uuid]
	(let [impl (or interop runtime-interop/*runtime-interop* *ability-interop*)]
		(when impl
			(runtime-interop/get-player-main-hand-item impl player-uuid))))

(defn get-block-entity-at
	[interop world-id x y z]
	(let [impl (or interop runtime-interop/*runtime-interop* *ability-interop*)]
		(when impl
			(runtime-interop/get-block-entity-at impl world-id x y z))))