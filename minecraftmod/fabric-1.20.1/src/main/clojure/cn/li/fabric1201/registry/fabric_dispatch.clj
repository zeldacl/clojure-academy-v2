(ns cn.li.fabric1201.registry.fabric-dispatch
  "Fabric 1.20.1 registry dispatch.

  Avoids touching Minecraft registries during AOT namespace load."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log]))

(defn- register-into-builtins!
  "Runtime-safe registration into BuiltInRegistries using reflection."
  [registry-field entry-id instance]
  (let [registry-class (Class/forName "net.minecraft.core.Registry")
        builtins-class (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
        rl-class (Class/forName "net.minecraft.resources.ResourceLocation")
        builtins-registry (.get (.getField builtins-class registry-field) nil)
        rl (clojure.lang.Reflector/invokeConstructor
             rl-class
             (to-array [modid/*mod-id* (str entry-id)]))]
    (clojure.lang.Reflector/invokeStaticMethod
      registry-class
      "register"
      (to-array [builtins-registry rl instance]))
    instance))

(defn register-block
  "Fabric-side callable wrapper used by mod.clj."
  [block-id block-instance]
  (log/info "Registering block with Fabric BuiltInRegistries:" block-id)
  (register-into-builtins! "BLOCK" block-id block-instance))

(defn register-item
  "Fabric-side callable wrapper used by mod.clj."
  [item-id item-instance]
  (log/info "Registering item with Fabric BuiltInRegistries:" item-id)
  (register-into-builtins! "ITEM" item-id item-instance))
