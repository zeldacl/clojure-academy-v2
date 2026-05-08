(ns cn.li.fabric1201.registry.fabric-dispatch
  "Fabric 1.20.1 registry placeholder.

  NOTE: Compile-unblocking stub. Avoids touching Minecraft registries during AOT."
  (:require [cn.li.mcmod.registry.platform :as registry]
            [cn.li.mc1201.registry.dispatch :as dispatch]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

(defn- register-into-builtins!
  "Runtime-safe registration into BuiltInRegistries using reflection.
  Avoids bootstrap-sensitive class loading during AOT namespace load."
  [registry-field entry-id instance]
  (let [registry-class (Class/forName "net.minecraft.core.Registry")
        builtins-class (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
        rl-class (Class/forName "net.minecraft.resources.ResourceLocation")
        builtins-registry (.get (.getField builtins-class registry-field) nil)
        rl (clojure.lang.Reflector/invokeConstructor
             rl-class
             (to-array [modid/MOD-ID (str entry-id)]))]
    (clojure.lang.Reflector/invokeStaticMethod
      registry-class
      "register"
      (to-array [builtins-registry rl instance]))
    instance))

(defmethod registry/register-block :fabric-1.20.1
  [block-id block-instance]
  (log/info "Registering block with Fabric BuiltInRegistries:" block-id)
  (register-into-builtins! "BLOCK" block-id block-instance))

(defmethod registry/register-item :fabric-1.20.1
  [item-id item-instance]
  (log/info "Registering item with Fabric BuiltInRegistries:" item-id)
  (register-into-builtins! "ITEM" item-id item-instance))

(defn register-block
  "Fabric-side callable wrapper used by mod.clj."
  [block-id block-instance]
  (dispatch/register-block! :fabric-1.20.1 block-id block-instance))

(defn register-item
  "Fabric-side callable wrapper used by mod.clj."
  [item-id item-instance]
  (dispatch/register-item! :fabric-1.20.1 item-id item-instance))
