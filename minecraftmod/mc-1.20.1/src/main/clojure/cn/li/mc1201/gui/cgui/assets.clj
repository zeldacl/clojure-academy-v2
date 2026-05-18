(ns cn.li.mc1201.gui.cgui.assets
  "CLIENT-ONLY texture/resource helpers for CGUI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.platform.resource :as res])
  (:import [net.minecraft.resources ResourceLocation]))

(defn ensure-resource-location
  [v]
  (cond
    (nil? v) nil
    (instance? ResourceLocation v) v
    (string? v) (if (re-find #":" v)
                  (let [[ns path] (str/split v #":" 2)]
                    (res/invoke-resource-location ns path))
                  (cond
                    (str/starts-with? v "assets/")
                    (let [after (subs v (count "assets/"))
                          parts (str/split after #"/" 2)
                          ns (first parts)
                          path (second parts)]
                      (if (and ns path)
                        (res/invoke-resource-location ns path)
                        (res/invoke-resource-location nil v)))
                    :else
                    (res/invoke-resource-location modid/*mod-id* v)))
    :else nil))

(defn resource-location->asset-path
  [resource-location]
  (when resource-location
    (let [[ns path] (str/split (str resource-location) #":" 2)]
      (when (and ns path)
        (str "assets/" ns "/" path)))))

(defn get-texture-size-from-resource
  [resource-location]
  (when-let [asset-path (resource-location->asset-path resource-location)]
    (when-let [resource (or (io/resource asset-path)
                            (io/resource asset-path (.getClassLoader (class get-texture-size-from-resource))))]
      (with-open [stream (io/input-stream resource)]
        (when-let [image (javax.imageio.ImageIO/read stream)]
          [(.getWidth image) (.getHeight image)])))))
