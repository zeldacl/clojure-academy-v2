(ns cn.li.mcmod.platform.ui
	"Platform-neutral UI widget factory registry.

	Widget ids and payload schemas are content-owned; mcmod only stores and invokes
	registered factories."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private widget-factories
	(atom {}))

(defn register-widget-factory!
	[widget-key factory-fn]
	(when-not (keyword? widget-key)
		(throw (ex-info "Widget key must be a keyword" {:widget-key widget-key})))
	(when-not (fn? factory-fn)
		(throw (ex-info "Widget factory must be a function" {:widget-key widget-key
																													:factory factory-fn})))
	(swap! widget-factories assoc widget-key factory-fn)
	nil)

(defn register-widget-factories!
	[factory-map]
	(doseq [[widget-key factory-fn] factory-map]
		(register-widget-factory! widget-key factory-fn))
	nil)

(defn create-widget
	[widget-key payload]
	(if-let [factory-fn (get @widget-factories widget-key)]
		(factory-fn payload)
		(do
			(log/warn "UI widget factory not registered" {:widget-key widget-key})
			nil)))