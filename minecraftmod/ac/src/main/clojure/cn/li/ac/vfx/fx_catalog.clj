(ns cn.li.ac.vfx.fx-catalog
  "Public VFX catalog facade. Production content is the directory-backed V3
   catalog; the old manifest loader is intentionally removed from runtime."
  (:require [cn.li.ac.vfx.fx-catalog-v3 :as v3]))

(defn assemble
  ([] (v3/assemble))
  ([opts] (v3/assemble opts)))
