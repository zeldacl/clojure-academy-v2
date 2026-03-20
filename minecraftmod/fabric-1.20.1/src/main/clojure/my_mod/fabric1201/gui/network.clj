(ns cn.li.fabric1201.gui.network
  "Fabric 1.20.1 GUI Network Packet System (stub).

  The earlier implementation used Yarn-era networking types. This project
  uses official Mojang mappings on Fabric, so custom packet code should be
  reintroduced later using Mojmap types (e.g. FriendlyByteBuf, ResourceLocation)."
  (:require [cn.li.mcmod.util.log :as log]))

(defn init-server!
  []
  (log/info "Fabric GUI networking stub (server)"))

(defn init-client!
  []
  (log/info "Fabric GUI networking stub (client)"))

