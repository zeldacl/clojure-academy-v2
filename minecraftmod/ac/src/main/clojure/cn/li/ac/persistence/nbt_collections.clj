(ns cn.li.ac.persistence.nbt-collections
  "Native-NBT collection helpers for AC-owned persistence schemas."
  (:require [cn.li.mcmod.platform.nbt :as nbt]))

(defn write-keyword-set! [compound key values]
  (let [result (nbt/create-nbt-list)]
    (doseq [value values]
      (let [entry (nbt/create-nbt-compound)]
        (nbt/nbt-set-string! entry "v" (if-let [ns-part (namespace value)]
                                          (str ns-part "/" (name value))
                                          (name value)))
        (nbt/nbt-append! result entry)))
    (nbt/nbt-set-tag! compound key result)))

(defn read-keyword-set [compound key]
  (let [values (nbt/nbt-get-list compound key)
        size (long (or (some-> values nbt/nbt-list-size) 0))]
    (loop [index 0 result (transient #{})]
      (if (< index size)
        (let [entry (nbt/nbt-list-get-compound values index)]
          (recur (unchecked-inc-int index)
                 (conj! result (keyword (nbt/nbt-get-string entry "v")))))
        (persistent! result)))))

(defn write-int-set! [compound key values]
  (let [result (nbt/create-nbt-list)]
    (doseq [value values]
      (let [entry (nbt/create-nbt-compound)]
        (nbt/nbt-set-int! entry "v" (int value))
        (nbt/nbt-append! result entry)))
    (nbt/nbt-set-tag! compound key result)))

(defn read-int-set [compound key]
  (let [values (nbt/nbt-get-list compound key)
        size (long (or (some-> values nbt/nbt-list-size) 0))]
    (loop [index 0 result (transient #{})]
      (if (< index size)
        (let [entry (nbt/nbt-list-get-compound values index)]
          (recur (unchecked-inc-int index)
                 (conj! result (int (nbt/nbt-get-int entry "v")))))
        (persistent! result)))))
