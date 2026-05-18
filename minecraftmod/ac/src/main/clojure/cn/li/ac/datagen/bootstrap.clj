(ns cn.li.ac.datagen.bootstrap
  "AC datagen metadata bootstrap.

  Platform datagen entrypoints call this after shared content initialization.
  Keeping these registrations in AC prevents mc-1.20.1 from depending on AC
  business namespaces."
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
