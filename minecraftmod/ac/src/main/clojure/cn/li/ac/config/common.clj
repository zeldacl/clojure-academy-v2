(ns cn.li.ac.config.common
  "Shared AC configuration constants used by runtime and tests."
  (:require [cn.li.mcmod.config.registry :as config-reg]))

(def wireless-domain
  "Config registry domain for AcademyCraft wireless settings."
  :cn.li.ac/wireless)

(defn wireless-config
  "Return current wireless domain config map from mcmod registry."
  []
  (config-reg/get-config-values wireless-domain))
