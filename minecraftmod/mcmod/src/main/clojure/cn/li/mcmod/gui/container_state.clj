(ns cn.li.mcmod.gui.container-state
  "Container lifecycle state storage for GUI infrastructure.")

(defonce active-containers
  (atom #{}))

(defonce player-containers
  (atom {}))

(defonce menu-containers
  (atom {}))

(defonce containers-by-id
  (atom {}))

(defonce client-container
  (atom nil))
