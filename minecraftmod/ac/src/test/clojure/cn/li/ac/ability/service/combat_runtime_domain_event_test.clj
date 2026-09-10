(ns cn.li.ac.ability.service.combat-runtime-domain-event-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]))

(deftest block-impact-seed-map-fails-loudly
  "Unresolved {:ref ...} maps must not be long-cast (ClassCastException).
   The graph compiler is supposed to lower them; this is the fail-loud net."
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"must be a number"
                        (combat-runtime/dispatch-domain-event!
                         {:type :world/block-impact
                          :payload {:world-id "minecraft:overworld"
                                    :position [0.0 64.0 0.0]
                                    :block-position [0.0 64.0 0.0]
                                    :seed {:nid :n/n-seed :ref [:local :seed]}}}))))
