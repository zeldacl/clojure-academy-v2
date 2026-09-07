(ns cn.li.ac.ability.skills-catalog
  "Production AC skill catalog facade.

   The public catalog is the directory-backed AC V3 loader.  Keeping this
   namespace as a tiny facade avoids leaking the resource format into the
   ability runtime while making the V3 cutover a single dependency change."
  (:require [cn.li.ac.ability.skills-catalog-v3 :as v3]))

(defn assemble
  ([] (v3/assemble))
  ([opts] (v3/assemble opts)))

