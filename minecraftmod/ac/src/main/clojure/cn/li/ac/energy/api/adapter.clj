(ns cn.li.ac.energy.api.adapter
  "Compatibility adapter from legacy `energy.operations` style calls to the
  Phase C protocol-based energy API.

  This keeps old call sites stable while giving new code an explicit bridge."
  (:require [cn.li.ac.energy.api.impl :as impl]
            [cn.li.ac.energy.api.protocol :as proto]))

(defn energy-system
  "Return the default protocol implementation instance."
  []
  (impl/energy-system))

(defn register-provider!
  "Register an energy provider and return its id."
  [id value]
  (impl/register-provider! id value))

(defn unregister-provider!
  "Unregister an energy provider id."
  [id]
  (impl/unregister-provider! id))

(defn get-energy
  [id]
  (proto/get-energy (energy-system) id))

(defn get-capacity
  [id]
  (proto/get-capacity (energy-system) id))

(defn set-energy!
  [id amount]
  (proto/set-energy (energy-system) id amount))

(defn transfer-energy!
  [source-id dest-id amount]
  (proto/transfer-energy (energy-system) source-id dest-id amount))

(defn drain-energy!
  [id amount]
  (proto/drain-energy (energy-system) id amount))

(defn list-providers
  []
  (proto/list-energy-providers (energy-system)))
