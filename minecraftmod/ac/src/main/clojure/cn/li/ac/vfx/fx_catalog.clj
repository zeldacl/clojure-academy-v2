(ns cn.li.ac.vfx.fx-catalog
  "Public VFX catalog facade backed by V4 free-form graphs."
  (:require [cn.li.ac.vfx.fx-catalog-v4 :as v4]))
(defn assemble ([] (v4/assemble)) ([opts] (v4/assemble opts)))
