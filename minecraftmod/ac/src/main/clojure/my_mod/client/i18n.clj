(ns my-mod.client.i18n
  "Client-side translation helper.

  Uses Minecraft's I18n on the client. On server/datagen, this namespace may
  still be loaded indirectly; in that case, fall back to returning the key."
  (:import [net.minecraft.client.resources.language I18n]))

(defn ^{:clj-kondo/ignore [:redefined-var]} translate
  "Translate a language key to the current locale string.
   Returns key itself if translation is unavailable."
  [k]
  (try
    (I18n/get (str k))
    (catch Throwable _ (str k))))

