(ns cn.li.mcmod.platform.ui
	"Platform-neutral UI widget factory registry.

	Widget ids and payload schemas are content-owned; mcmod only stores and invokes
	registered factories."
	(:require [cn.li.mcmod.util.log :as log]))

(defn create-widget-factory-runtime
	([] (create-widget-factory-runtime {}))
	([{:keys [state*]}]
	 {:cn.li.mcmod.platform.ui/runtime ::widget-factory-runtime
	  :state* (or state* (atom {}))}))

(def ^:dynamic *widget-factory-runtime* nil)

(def ^:private _widget-factory-runtime (delay (create-widget-factory-runtime)))

(defn- widget-factories-atom []
	(:state* (or *widget-factory-runtime* @_widget-factory-runtime)))

(defn- widget-factories-snapshot []
	@(widget-factories-atom))

(defn register-widget-factory!
	[widget-key factory-fn]
	(when-not (keyword? widget-key)
		(throw (ex-info "Widget key must be a keyword" {:widget-key widget-key})))
	(when-not (fn? factory-fn)
		(throw (ex-info "Widget factory must be a function" {:widget-key widget-key
																													:factory factory-fn})))
	(let [prev (get (widget-factories-snapshot) widget-key)]
		(when (and (some? prev) (not= prev factory-fn))
			(throw (ex-info "Duplicate UI widget factory registration"
											{:widget-key widget-key :previous prev :incoming factory-fn}))))
	(swap! (widget-factories-atom) assoc widget-key factory-fn)
	nil)

(defn reset-widget-factory-registry!
	"Test-only reset of widget factory registry."
	[]
	(swap! (widget-factories-atom) (constantly {}))
	nil)

(defn register-widget-factories!
	[factory-map]
	(doseq [[widget-key factory-fn] factory-map]
		(register-widget-factory! widget-key factory-fn))
	nil)

(defn create-widget
	[widget-key payload]
	(if-let [factory-fn (get (widget-factories-snapshot) widget-key)]
		(factory-fn payload)
		(do
			(log/warn "UI widget factory not registered" {:widget-key widget-key})
			nil)))