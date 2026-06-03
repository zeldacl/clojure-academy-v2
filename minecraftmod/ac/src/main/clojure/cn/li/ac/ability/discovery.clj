(ns cn.li.ac.ability.discovery
  "Discovery layer for AC ability content.

  Scanner is the single source of truth for skill/fx namespaces."
  (:require [cn.li.ac.discovery.scanner :as scanner]
            [cn.li.mcmod.util.log :as log]))

(defn discovered-skill-namespaces
  "Return server-side skill namespaces in deterministic load order."
  []
  (let [{:keys [skill]} (scanner/discover-ability-namespaces)]
    (when (empty? skill)
      (log/warn "Ability scanner returned no skill namespaces"))
    (vec skill)))

(defn discovered-fx-namespaces
  "Return client-side FX namespaces in deterministic load order."
  []
  (let [{:keys [fx]} (scanner/discover-ability-namespaces)]
    (when (empty? fx)
      (log/warn "Ability scanner returned no fx namespaces"))
    (vec fx)))