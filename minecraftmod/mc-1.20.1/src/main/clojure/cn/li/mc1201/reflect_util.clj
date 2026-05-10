(ns cn.li.mc1201.reflect-util)

(defn class-noinit
  ^Class [^String class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defn ctor
  [^Class cls & args]
  (clojure.lang.Reflector/invokeConstructor cls (to-array args)))

(defn inst
  [target method-name & args]
  (clojure.lang.Reflector/invokeInstanceMethod target method-name (to-array args)))

(defn static
  [^Class cls method-name & args]
  (let [^String method-name (str method-name)]
    (clojure.lang.Reflector/invokeStaticMethod cls method-name (to-array args))))

(defn field
  [target field-name]
  (clojure.lang.Reflector/getInstanceField target field-name))

(defn make-rl
  [id]
  (let [rl-cls (class-noinit "net.minecraft.resources.ResourceLocation")]
    (ctor rl-cls (str id))))

(defn try-call
  [f fallback]
  (try
    (f)
    (catch Throwable _ fallback)))

(defn stack-empty?
  [stack]
  (or (nil? stack)
      (try-call #(boolean (inst stack "isEmpty")) true)))

(defn player-main-hand-stack
  [player]
  (or
   (try-call
    #(let [ih-cls (class-noinit "net.minecraft.world.InteractionHand")
           main-hand (.get (.getField ih-cls "MAIN_HAND") nil)]
       (inst player "getItemInHand" main-hand))
    nil)
   (try-call #(inst player "getMainHandItem") nil)))
