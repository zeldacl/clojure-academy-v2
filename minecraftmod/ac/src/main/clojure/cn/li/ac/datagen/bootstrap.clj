(ns cn.li.ac.datagen.bootstrap
  "AC datagen metadata bootstrap.

  AC registers this through mcmod.lifecycle so platform datagen entrypoints do
  not depend on AC business namespaces."
  (:require [cn.li.ac.ability.datagen.registry :as ability-datagen]
            [cn.li.ac.wireless.datagen.registry :as wireless-datagen]
            [cn.li.ac.energy.datagen.registry :as energy-datagen]))

(defn register-datagen-metadata!
  "Register datagen metadata from all AC business domains.

  Each domain registry populates shared mcmod.datagen.metadata atoms used by
  mc-1.20.1 datagen providers."
  []
  (ability-datagen/register-datagen-metadata!)
  (wireless-datagen/register-datagen-metadata!)
  (energy-datagen/register-datagen-metadata!)
  nil)
