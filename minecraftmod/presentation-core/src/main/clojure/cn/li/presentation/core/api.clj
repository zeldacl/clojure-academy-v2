(ns cn.li.presentation.core.api
  "The public surface of presentation-core, for content modules (ac's
   gui/presentation.clj and gui/reactive/register.clj today; a future
   bc/cc would consume the same surface).

   cn.li.presentation.core.host/api stays as-is: it is the Java-bridge
   adapter (a map of closures with no arglists, docstrings or arity
   checking, one call site per operation) consumed by
   cn.li.mcmod.runtime.presentation-bridge, not something this facade
   should wrap a second time. This namespace adds real defns for the
   direct-Clojure-caller side: create-runtime/api (re-exported from host)
   plus artifact loading, sized from ac's actual two consumption points."
  (:require [cn.li.presentation.core.host :as host]
            [cn.li.presentation.core.artifact :as artifact]))

(defn create-runtime [] (host/create-runtime))
(defn api [runtime] (host/api runtime))

(defn load-view
  ([view-id] (artifact/load-view view-id))
  ([manifest view-id] (artifact/load-view manifest view-id)))
(defn load-manifest [] (artifact/load-manifest))
