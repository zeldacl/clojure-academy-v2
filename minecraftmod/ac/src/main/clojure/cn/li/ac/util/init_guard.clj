(ns cn.li.ac.util.init-guard
  "Macros for one-time namespace initialization guards.")

(defmacro defonce-guard
  "Define a private atom guard initialized to false."
  [sym]
  `(defonce ^:private ~sym (atom false)))

(defmacro with-init-guard
  "Execute body exactly once per guard atom using compare-and-set! semantics."
  [guard & body]
  `(when (compare-and-set! ~guard false true)
     ~@body))
