(ns cn.li.forge1201.capability.cap-invalidation-test
  (:require [clojure.test :refer [deftest is]]))

(defn- read-forge-cap-handler-source []
  (let [candidates ["forge-1.20.1/src/main/java/cn/li/forge1201/capability/ForgeCapabilityHandler.java"
                    "../forge-1.20.1/src/main/java/cn/li/forge1201/capability/ForgeCapabilityHandler.java"]]
    (some (fn [rel]
            (let [f (java.io.File. (str (System/getProperty "user.dir") "/" rel))]
              (when (.exists f) (slurp f))))
          candidates)))

(deftest capability-handler-keeps-lazy-optional-cache-test
  (let [src (read-forge-cap-handler-source)]
    (is (some? src))
    (when src
      (is (re-find #"invalidateCaps" src))
      (is (re-find #"reviveCaps" src))
      (is (re-find #"LazyOptional" src)))))
