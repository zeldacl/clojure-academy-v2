(ns cn.li.forge1201.compile-bootstrap
	(:import [cn.li.forge1201.build CompileTimeBootstrap]))

(defn- compile-bootstrap-enabled?
	[]
	(= "true" (System/getProperty "ac.enable.compile.bootstrap" "false")))

(defonce ^:private bootstrapped?
	(do
		(when (compile-bootstrap-enabled?)
			(CompileTimeBootstrap/ensureBootstrapped))
		true))