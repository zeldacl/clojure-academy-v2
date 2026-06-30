(ns cn.li.mcmod.client.texture-registry
  "Platform-neutral registry for named texture paths.

  Content modules register keyword → path-string mappings during client init.
  Platform layers (mc-1.20.1, forge, fabric) resolve path strings to
  platform-specific texture handles at render time.")

(defonce ^:private registry* (atom {}))

(defn register-texture!
  "Register a named texture path. key is a keyword; path is a string of the form
   'textures/guis/...' or 'mod-id:textures/guis/...'."
  [key path]
  (swap! registry* assoc key (str path))
  nil)

(defn get-texture-path
  "Return the path string registered under key, or nil."
  [key]
  (get @registry* key))

(defn reset-texture-registry-for-test!
  []
  (reset! registry* {})
  nil)
