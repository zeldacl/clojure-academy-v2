(ns my-mod.util.log)

(defn info [& xs]
  (locking System/out
    (println (str "[my_mod] " (apply str xs)))))

(defn debug [& xs]
  (locking System/out
    (println (str "[my_mod DEBUG] " (apply str xs)))))

(defn error [& xs]
  (locking System/err
    (binding [*out* *err*]
      (println (str "[my_mod ERROR] " (apply str xs))))))
