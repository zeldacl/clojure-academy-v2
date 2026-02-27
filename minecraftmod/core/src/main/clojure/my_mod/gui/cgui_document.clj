(ns my-mod.gui.cgui-document
  "Wrapper for LambdaLib2 CGUIDocument loading"
  (:import [cn.lambdalib2.cgui.loader CGUIDocument]
           [net.minecraft.util ResourceLocation]))

(defn read-xml
  "Read CGUI XML document from resource location string"
  [resource-loc]
  (CGUIDocument/read (ResourceLocation. resource-loc)))

(defn get-widget
  "Get named widget from CGUIDocument"
  [doc name]
  (.getWidget doc name))(ns my-mod.gui.cgui-document
  "Wrapper for LambdaLib2 CGUIDocument loading"
  (:import [cn.lambdalib2.cgui.loader CGUIDocument]
           [net.minecraft.util ResourceLocation]))

(defn read-xml
  "Read CGUI XML document from resource location string"
  [resource-loc]
  (CGUIDocument/read (ResourceLocation. resource-loc)))

(defn get-widget
  "Get named widget from CGUIDocument"
  [doc name]
  (.getWidget doc name))
