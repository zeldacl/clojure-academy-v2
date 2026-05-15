(ns cn.li.ac.wireless.service.node-connection
  "Service wrappers for node-connection read/write operations."
  (:require [cn.li.ac.wireless.data.node-conn :as node-conn]))

(def get-node node-conn/get-node)
(def get-load node-conn/get-load)
(def get-capacity node-conn/get-capacity)
(def remove-generator! node-conn/remove-generator!)
(def remove-receiver! node-conn/remove-receiver!)
