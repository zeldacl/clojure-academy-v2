(ns my-mod.forge1165.datagen.annotations
  "Annotation definitions for Forge 1.16.5
   
   Provides the @Mod.EventBusSubscriber annotation compatibility."
  (:import [net.minecraftforge.fml.common Mod]
           [net.minecraftforge.api.distmarker Dist])
  (:gen-class
   :name com.example.my_mod1165.datagen.EventBusSubscriberAnnotation
   :extends java.lang.Object))

;; Note: The actual @Mod.EventBusSubscriber annotation will be applied
;; via reflection or bytecode modification. This is a placeholder for type safety.
