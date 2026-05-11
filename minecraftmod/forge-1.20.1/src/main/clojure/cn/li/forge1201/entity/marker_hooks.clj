(ns cn.li.forge1201.entity.marker-hooks
	"Forge thin wrapper delegating scripted marker hook registration to shared mc1201 implementation."
	(:require [cn.li.mc1201.entity.marker-hooks :as shared]))

(defn register-all-marker-hooks!
	[]
	(shared/register-all-marker-hooks!))