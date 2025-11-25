(ns my-mod.util.log)

(defn info [& xs]
  (locking System/out
    (println (str "[my_mod] " (apply str xs)))))
