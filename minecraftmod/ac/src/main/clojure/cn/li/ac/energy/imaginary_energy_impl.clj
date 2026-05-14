(ns cn.li.ac.energy.imaginary-energy-impl
	"Default imaginary-energy implementation for AC items."
	(:require [cn.li.ac.energy.energy-type-interface :as energy-type]
						[cn.li.ac.energy.service.item-manager :as item-manager]))

(defrecord ImaginaryEnergyType []
	energy-type/EnergyType
	(energy-type-id [_] :imaginary-energy)
	(energy-type-name [_] "Imaginary Energy")
	(supports-item? [_ item-stack]
		(item-manager/is-energy-item-supported? item-stack))
	(get-energy* [_ item-stack]
		(item-manager/get-item-energy item-stack))
	(get-capacity* [_ item-stack]
		(item-manager/get-item-capacity item-stack))
	(get-bandwidth* [_ item-stack]
		(item-manager/get-item-bandwidth item-stack))
	(set-energy*! [_ item-stack amount]
		(item-manager/set-item-energy! item-stack amount))
	(charge-item*! [_ item-stack amount ignore-bandwidth]
		(item-manager/charge-energy-to-item item-stack amount ignore-bandwidth))
	(discharge-item*! [_ item-stack amount ignore-bandwidth]
		(item-manager/pull-energy-from-item item-stack amount ignore-bandwidth)))

(defonce ^:private default-imaginary-energy-type
	(delay (energy-type/register-energy-type! (->ImaginaryEnergyType))))

(defn register-default-energy-type!
	[]
	@default-imaginary-energy-type)

(defn imaginary-energy-type
	[]
	(register-default-energy-type!))