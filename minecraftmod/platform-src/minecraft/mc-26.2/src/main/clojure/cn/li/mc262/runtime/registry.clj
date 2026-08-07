(ns cn.li.mc262.runtime.registry
  "Lazy access to 26.2 built-in registries without class-load bootstrap side effects.")

(defn builtin [field-name]
  (let [klass (Class/forName "net.minecraft.core.registries.BuiltInRegistries")]
    (.get (.getField klass field-name) nil)))
