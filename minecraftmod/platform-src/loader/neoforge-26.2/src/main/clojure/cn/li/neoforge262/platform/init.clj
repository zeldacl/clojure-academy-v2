(ns cn.li.neoforge262.platform.init
  "NeoForge 26.2 platform initializer."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mc262.bootstrap.init-common :as mc-init]))

(defn init-platform!
  "Catalog entrypoint for neoforge-26.2. Wires mc-26.2 / mcbase runtime."
  []
  (log/info "[neoforge262] init-platform!")
  (mc-init/init!)
  nil)
