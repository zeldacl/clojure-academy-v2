(ns cn.li.ac.gui.node-id-probe-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.ac.config.modid :as modid]))

(deftest node-id-probe-test
  (let [spec (ui-xml/load-spec (modid/namespaced-path "guis/rework/new/page_solar.xml"))
        r (rt/create-runtime)
        _ (rt/build! r spec)
        by-kw (rt/node-by-id r :ui_block)
        by-str (rt/node-by-id r "ui_block")]
    (println "PROBE by-keyword =" (pr-str by-kw))
    (println "PROBE by-string  =" (pr-str by-str))
    (is (some? by-str))))
