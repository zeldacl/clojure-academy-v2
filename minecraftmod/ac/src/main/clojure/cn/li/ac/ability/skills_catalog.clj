(ns cn.li.ac.ability.skills-catalog
  "Production AC skill catalog facade backed by V4 free-form graphs."
  (:require [cn.li.ac.ability.skills-catalog-v4 :as v4]))
(defn assemble ([] (v4/assemble)) ([opts] (v4/assemble opts)))
