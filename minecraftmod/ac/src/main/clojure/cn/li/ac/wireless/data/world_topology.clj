(ns cn.li.ac.wireless.data.world-topology
  "Stable topology boundary for wireless world operations.

  Low-level index mutation now lives in `wireless.data.topology-index` and
  business command orchestration lives in `wireless.service.topology-service`."
  (:require [cn.li.ac.wireless.data.topology-index :as topology-index]
            [cn.li.ac.wireless.service.topology-service :as topology-service]))

(def add-to-spatial-index! topology-index/add-to-spatial-index!)
(def remove-from-spatial-index! topology-index/remove-from-spatial-index!)

(def get-network-by-matrix topology-index/get-network-by-matrix)
(def get-network-by-node topology-index/get-network-by-node)
(def get-network-by-ssid topology-index/get-network-by-ssid)
(def get-node-connection topology-index/get-node-connection)

(def register-network! topology-index/register-network!)
(def register-network-node! topology-index/register-network-node!)
(def unregister-network-node! topology-index/unregister-network-node!)
(def unregister-network! topology-index/unregister-network!)

(def register-node-connection! topology-index/register-node-connection!)
(def register-node-device! topology-index/register-node-device!)
(def unregister-node-device! topology-index/unregister-node-device!)
(def unregister-node-connection! topology-index/unregister-node-connection!)

(def create-network-impl! topology-service/create-network!)
(def destroy-network-impl! topology-service/destroy-network!)
(def create-node-connection-impl! topology-service/create-node-connection!)
(def destroy-node-connection-impl! topology-service/destroy-node-connection!)
(def ensure-node-connection! topology-service/ensure-node-connection!)
(def link-node-to-network! topology-service/link-node-to-network!)
(def link-generator-to-node-connection! topology-service/link-generator-to-connection!)
(def link-receiver-to-node-connection! topology-service/link-receiver-to-connection!)

(def rebuild-network-indexes! topology-index/rebuild-network-indexes!)
(def rebuild-connection-indexes! topology-index/rebuild-connection-indexes!)
