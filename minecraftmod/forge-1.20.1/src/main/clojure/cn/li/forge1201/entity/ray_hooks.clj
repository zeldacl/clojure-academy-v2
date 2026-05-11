(ns cn.li.forge1201.entity.ray-hooks
	"Forge thin wrapper delegating scripted ray hook registration to shared mc1201 implementation."
	(:require [cn.li.mc1201.entity.ray-hooks :as shared]))

(def ^:private impl-key->hook-class
	{:owner-follow "cn.li.mc1201.entity.hook.ray.OwnerFollowRayHook"})

(defn register-all-ray-hooks!
	[]
	(shared/register-all-ray-hooks!))