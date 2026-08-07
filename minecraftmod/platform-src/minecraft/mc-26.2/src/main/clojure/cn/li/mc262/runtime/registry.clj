(ns cn.li.mc262.runtime.registry
  "Shared access to 26.2 built-in registries."
  (:import [net.minecraft.core.registries BuiltInRegistries]))

(defn builtin
  [field-name]
  (case field-name
    "ITEM" BuiltInRegistries/ITEM
    "BLOCK" BuiltInRegistries/BLOCK
    "SOUND_EVENT" BuiltInRegistries/SOUND_EVENT
    "ENTITY_TYPE" BuiltInRegistries/ENTITY_TYPE
    (throw (ex-info "Unknown built-in registry" {:field field-name}))))
