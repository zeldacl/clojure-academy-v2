(ns my-mod.gui.cgui-document
  "Wrapper for LambdaLib2 CGUIDocument loading"
  (:require [my-mod.config.modid :as modid])
  (:import [cn.lambdalib2.cgui.loader CGUIDocument]))

(defn read-xml
  "Read CGUI XML document from resource location string"
  [resource-loc]
  (CGUIDocument/read (modid/resource-location resource-loc)))

(defn get-widget
  "Get named widget from CGUIDocument"
  [doc name]
  (.getWidget doc name))
