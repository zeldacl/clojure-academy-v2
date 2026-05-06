(ns cn.li.ac.terminal.freq-transmitter-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.apps.freq-transmitter :as ft]
            [cn.li.ac.terminal.app-registry :as reg]))

(deftest init-registers-app-once-test
  (reg/clear-registry!)
  (reset! (var-get (ns-resolve 'cn.li.ac.terminal.apps.freq-transmitter 'freq-transmitter-installed?)) false)
  (ft/init-freq-transmitter-app!)
  (ft/init-freq-transmitter-app!)
  (is (= 1 (reg/app-count)))
  (is (= :freq-transmitter (:id (reg/get-app :freq-transmitter)))))
