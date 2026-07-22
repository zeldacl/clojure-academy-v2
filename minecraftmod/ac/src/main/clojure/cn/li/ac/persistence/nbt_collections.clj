(ns cn.li.ac.persistence.nbt-collections
  "Native-NBT collection helpers for AC-owned persistence schemas."
  (:require [cn.li.mcmod.platform.nbt :as nbt]))

(defn write-keyword-set! [compound key values]
  (let [result (nbt/create-list)]
    (doseq [value values]
      (let [entry (nbt/create-compound)]
        (nbt/set-string! entry "v" (if-let [ns-part (namespace value)]
                                          (str ns-part "/" (name value))
                                          (name value)))
        (nbt/append! result entry)))
    (nbt/set-tag! compound key result)))

(defn read-keyword-set [compound key]
  (let [values (nbt/get-list compound key)
        size (long (or (some-> values nbt/list-size) 0))]
    (loop [index 0 result (transient #{})]
      (if (< index size)
        (let [entry (nbt/list-compound values index)]
          (recur (unchecked-inc-int index)
                 (conj! result (keyword (nbt/get-string entry "v")))))
        (persistent! result)))))

(defn write-int-set! [compound key values]
  (let [result (nbt/create-list)]
    (doseq [value values]
      (let [entry (nbt/create-compound)]
        (nbt/set-int! entry "v" (int value))
        (nbt/append! result entry)))
    (nbt/set-tag! compound key result)))

(defn read-int-set [compound key]
  (let [values (nbt/get-list compound key)
        size (long (or (some-> values nbt/list-size) 0))]
    (loop [index 0 result (transient #{})]
      (if (< index size)
        (let [entry (nbt/list-compound values index)]
          (recur (unchecked-inc-int index)
                 (conj! result (int (nbt/get-int entry "v")))))
        (persistent! result)))))
