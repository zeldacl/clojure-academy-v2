(ns my-mod.i18n
  "Platform-neutral i18n entrypoint.

  - `ac` / `mcmod` code should depend on THIS namespace only.
  - Platform modules (forge/fabric) should set `*translate-fn*` at init time."
  (:refer-clojure :exclude [format]))

(def ^:dynamic *translate-fn*
  "Function (fn [key] -> string). Defaults to identity; platform should bind/alter-var-root."
  (fn [k] (str k)))

(defn translate
  "Translate a language key using the platform-provided implementation.
   If none is installed, returns key string."
  [k]
  (*translate-fn* k))

