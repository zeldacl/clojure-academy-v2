(ns cn.li.mcmod.util.log
  (:import [org.slf4j Logger LoggerFactory]))

(def ^Logger ^:private logger (LoggerFactory/getLogger "my_mod"))

(defn info [& xs]
  (.info logger (str "[my_mod] " (apply str xs))))

(defn debug [& xs]
  (.debug logger (str "[my_mod DEBUG] " (apply str xs))))

(defn warn [& xs]
  (.warn logger (str "[my_mod WARN] " (apply str xs))))

(defn error [& xs]
  (.error logger (str "[my_mod ERROR] " (apply str xs))))
