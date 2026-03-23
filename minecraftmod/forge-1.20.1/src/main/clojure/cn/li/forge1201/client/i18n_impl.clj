(ns cn.li.forge1201.client.i18n-impl
  "CLIENT-ONLY: Client-side i18n implementation for Forge 1.20.1.

  This namespace wraps net.minecraft.client.resources.language.I18n and must
  only be loaded on the client side via side-checked requiring-resolve."
  (:require [cn.li.mcmod.i18n :as i18n])
  (:import [net.minecraft.client.resources.language I18n]))

(defn translate
  "Translate a key using client-side I18n.

  Args:
    k: String or keyword - Translation key

  Returns:
    Translated string, or the key itself if translation not found."
  [k]
  (try
    (I18n/get (str k) (object-array 0))
    (catch Throwable _
      (str k))))

(defn install-client-i18n!
  "Install client-side i18n implementation into mcmod's i18n system.

  This should be called during FMLClientSetupEvent to enable client-side
  translations in platform-agnostic code."
  []
  (alter-var-root #'i18n/*translate-fn* (constantly translate))
  nil)
