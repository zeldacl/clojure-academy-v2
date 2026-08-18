(ns cn.li.combat.frame
  "Reusable Java-array execution-frame construction." 
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame]
           [java.util ArrayList]))

(defn create-frame
  [{double-count :double long-count :long boolean-count :boolean object-count :object}]
  (ExecutionFrame.
    (double-array (max 1 (clojure.core/long (or double-count 0))))
    (long-array (max 1 (clojure.core/long (or long-count 0))))
    (boolean-array (max 1 (clojure.core/long (or boolean-count 0))))
    (object-array (max 1 (clojure.core/long (or object-count 0))))
    (ArrayList. 32)
    (ArrayList. 16)
    (ArrayList. 16)
    (int-array (max 1 (clojure.core/long (or object-count 0))))))
