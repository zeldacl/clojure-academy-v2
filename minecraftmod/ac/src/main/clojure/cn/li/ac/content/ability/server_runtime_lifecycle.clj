(ns cn.li.ac.content.ability.server-runtime-lifecycle
	"Server lifecycle bootstrap for AC ability runtimes that must not outlive a server session."
	(:require [cn.li.ac.content.ability.meltdowner.damage-helper :as damage-helper]
						[cn.li.ac.content.ability.vecmanip.arbitration :as arbitration]
						[cn.li.ac.content.ability.vecmanip.vec-reflection :as vec-reflection]
						[cn.li.mcmod.util.log :as log]))

(def ^:private server-runtime-lifecycle-lock
	(Object.))

(def ^:private ^:dynamic *server-runtime-lifecycle-installed?*
	false)

(defn create-ability-server-runtime-bundle
	[]
	{:projectile-arbitration (arbitration/create-projectile-arbitration-runtime)
	 :vec-reflection-runtime (vec-reflection/create-vec-reflection-runtime)
	 :damage-helper-runtime (damage-helper/create-damage-helper-runtime)})

(defn install-ability-server-runtime-bundle!
	([]
	 (install-ability-server-runtime-bundle! (create-ability-server-runtime-bundle)))
	([{:keys [projectile-arbitration vec-reflection-runtime damage-helper-runtime] :as bundle}]
	 (when-not (map? bundle)
		 (throw (ex-info "Expected ability server runtime bundle"
										 {:bundle bundle})))
	 (arbitration/install-projectile-arbitration-runtime! projectile-arbitration)
	 (vec-reflection/install-vec-reflection-runtime! vec-reflection-runtime)
	 (damage-helper/install-damage-helper-runtime! damage-helper-runtime)
	 nil))

(defn clear-ability-server-runtime-bundle!
	[]
	(arbitration/clear-projectile-arbitration-runtime!)
	(vec-reflection/clear-vec-reflection-runtime!)
	(damage-helper/clear-damage-helper-runtime!)
	nil)

(defn reset-server-runtime-lifecycle-for-test!
	[]
	(locking server-runtime-lifecycle-lock
		(alter-var-root #'*server-runtime-lifecycle-installed?* (constantly false)))
	(clear-ability-server-runtime-bundle!)
	nil)

(defn install-server-runtime-lifecycle!
	[]
	(when-not (var-get #'*server-runtime-lifecycle-installed?*)
		(locking server-runtime-lifecycle-lock
			(when-not (var-get #'*server-runtime-lifecycle-installed?*)
				(install-ability-server-runtime-bundle!)
				(alter-var-root #'*server-runtime-lifecycle-installed?* (constantly true))
				(log/info "Installed AC ability server runtime bundle"))))
	nil)

(defn reset-ability-server-runtimes!
	[]
	(install-ability-server-runtime-bundle!)
	(log/info "Reset AC ability server runtime bundle")
	nil)