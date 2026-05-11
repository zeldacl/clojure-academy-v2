(ns cn.li.mc1201.client.i18n
  "CLIENT-ONLY shared i18n implementation for Minecraft 1.20.1."
  (:require [cn.li.mcmod.i18n :as i18n])
  (:import [net.minecraft.client.resources.language I18n]))

(defn translate
  [k]
  (try
    (I18n/get (str k) (object-array 0))
    (catch Throwable _
      (str k))))

(defn install-client-i18n!
  []
  (alter-var-root #'i18n/*translate-fn* (constantly translate))
  nil)
