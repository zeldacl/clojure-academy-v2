(ns cn.li.ac.wireless.service.world-registry
  "Service-level world registry boundary for wireless runtime data.

  This namespace intentionally centralizes access to world-scoped wireless
  state so higher-level API modules stop depending on `wireless.data.world`
  directly.

  It should remain a focused service boundary: expose stable reads/writes needed
  by application services, but keep persistence/index mutation mechanics in
  `wireless.data.*`. New service orchestration should depend on this boundary
  instead of reaching through to data internals."
  (:require [cn.li.ac.wireless.data.world :as wd]))

(def create-world-registry-runtime wd/create-world-registry-runtime)
(def call-with-world-registry-runtime wd/call-with-world-registry-runtime)

(def get-world-data wd/get-world-data)
(def get-world-data-non-create wd/get-world-data-non-create)

(def get-network-by-matrix wd/get-network-by-matrix)
(def get-network-by-node wd/get-network-by-node)
(def get-network-by-ssid wd/get-network-by-ssid)
(def get-node-connection wd/get-node-connection)

(def range-search-networks wd/range-search-networks)
(def get-nearby-chunks wd/get-nearby-chunks)
(def get-vblocks-in-chunks wd/get-vblocks-in-chunks)

(def create-network-impl! wd/create-network-impl!)
(def destroy-network-impl! wd/destroy-network-impl!)
(def ensure-node-connection! wd/ensure-node-connection!)
(def link-node-to-network! wd/link-node-to-network!)
(def link-generator-to-node-connection! wd/link-generator-to-node-connection!)
(def link-receiver-to-node-connection! wd/link-receiver-to-node-connection!)

(def tick-world-data! wd/tick-world-data!)
(def print-statistics wd/print-statistics)