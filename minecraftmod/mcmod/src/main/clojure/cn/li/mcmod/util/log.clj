(ns cn.li.mcmod.util.log
  (:import [org.slf4j Logger LoggerFactory]))

(def ^Logger ^:private logger (LoggerFactory/getLogger "my_mod"))

(defn info [& xs]
  (.info logger (str "[my_mod] " (apply str xs))))

(defn debug-enabled?
  "Returns true if DEBUG level logging is enabled for the mod logger.
  Use this to guard expensive debug-level format-string construction on hot paths."
  []
  (.isDebugEnabled logger))

(defn debug*
  "Internal: called by the `debug` macro expansion in other namespaces, must stay public."
  [^String s]
  (.debug logger s))

(defmacro debug
  "Guarded debug log: argument expressions are NOT evaluated unless DEBUG is enabled."
  [& xs]
  `(when (debug-enabled?)
     (debug* (str "[my_mod DEBUG] " ~@xs))))

(defn warn [& xs]
  (.warn logger (str "[my_mod WARN] " (apply str xs))))

(defn error [& xs]
  (.error logger (str "[my_mod ERROR] " (apply str xs))))

(defn stacktrace
  "Log an ERROR message with full stack trace for the given Throwable.
   msg: descriptive string, e: the exception/throwable to log."
  [msg ^Throwable e]
  (.error logger (str "[my_mod ERROR] " msg) e))
