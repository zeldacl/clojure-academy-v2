(ns cn.li.ac.test.support.nbt
  (:require [cn.li.mcmod.platform.nbt :as nbt]))

(defn- nk [k] (if (keyword? k) (name k) (str k)))

(defn atom-compound
  []
  (let [st (atom {})]
    (reify nbt/INBTCompound
      (nbt-set-int! [_ k v] (swap! st assoc (nk k) (int v)) _)
      (nbt-get-int [_ k] (int (get @st (nk k) 0)))
      (nbt-set-string! [_ k v] (swap! st assoc (nk k) (str v)) _)
      (nbt-get-string [_ k] (str (get @st (nk k) "")))
      (nbt-set-boolean! [_ k v] (swap! st assoc (nk k) (boolean v)) _)
      (nbt-get-boolean [_ k] (boolean (get @st (nk k) false)))
      (nbt-set-double! [_ k v] (swap! st assoc (nk k) (double v)) _)
      (nbt-get-double [_ k] (double (get @st (nk k) 0.0)))
      (nbt-set-float! [_ k v] (swap! st assoc (nk k) (float v)) _)
      (nbt-get-float [_ k] (float (get @st (nk k) 0.0)))
      (nbt-set-long! [_ k v] (swap! st assoc (nk k) (long v)) _)
      (nbt-get-long [_ k] (long (get @st (nk k) 0)))
      (nbt-set-tag! [_ k v] (swap! st assoc (nk k) v) _)
      (nbt-get-tag [_ k] (get @st (nk k)))
      (nbt-get-compound [_ k] (get @st (nk k)))
      (nbt-get-list [_ k] (get @st (nk k)))
      (nbt-has-key? [_ k] (contains? @st (nk k))))))
