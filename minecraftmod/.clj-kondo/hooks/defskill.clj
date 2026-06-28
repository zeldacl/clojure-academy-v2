(ns hooks.defskill
  (:require [clj-kondo.hooks-api :as api]))

(defn defskill [{:keys [node]}]
  (let [[_category sym & _body] (:children node)]
    ;; Replace (defskill cat sym ...) with (def sym nil)
    ;; — registers the var without bogus "Too many arguments to def" errors
    {:node (api/list-node
             [(api/token-node 'def)
              sym
              (api/token-node nil)])}))
