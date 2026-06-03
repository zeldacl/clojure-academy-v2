(ns cn.li.ac.block.wireless-matrix.block
  "Wireless Matrix block - thin coordinator."
  (:require [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.wireless-matrix.handlers :as matrix-handlers]
            [cn.li.ac.block.wireless-matrix.capability :as matrix-capability]
            [cn.li.ac.block.wireless-matrix.inventory :as matrix-inventory]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.block.wireless-matrix.state :as matrix-state]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

(defonce-guard wireless-matrix-installed?)

(defn- register-matrix-multiblock! []
  (bdsl/defmultiblock 'wireless-matrix
    :multi-block {:positions [[0 0 0] [0 0 1] [1 0 1] [1 0 0]
                              [0 1 0] [0 1 1] [1 1 1] [1 1 0]]
                  :rotation-center [1.0 0 1.0]}
    :common {:physical {:material :stone
                        :hardness 3.0
                        :resistance 6.0
                        :requires-tool true
                        :harvest-tool :pickaxe
                        :harvest-level 1
                        :sounds :stone}
             :rendering {:light-level 1.0}}
    :controller {:registry-name "matrix"
           :rendering {:flat-item-icon? true
             :render-shape :invisible}
                 :events {:on-right-click matrix-logic/handle-matrix-right-click
                          :on-place (matrix-logic/handle-matrix-place)
                          :on-break (matrix-logic/handle-matrix-break)}}
    :part {:registry-name "matrix_part"
          :rendering {:model-parent "minecraft:block/block"
            :render-shape :invisible}
           :events {:on-right-click matrix-logic/handle-matrix-right-click}}))

(defn init-wireless-matrix! []
  (machine-reg/init-machine!
    {:guard wireless-matrix-installed?
     :log-label "Wireless Matrix"
     :before matrix-inventory/ensure-matrix-slot-schema!
     :tile-kind {:tile-kind :wireless-matrix
                 :tick-fn matrix-logic/matrix-scripted-tick-fn
                 :read-nbt-fn matrix-state/matrix-scripted-load-fn
                 :write-nbt-fn matrix-state/matrix-scripted-save-fn}
     :tiles [{:id "wireless-matrix"
              :registry-name "matrix"
              :blocks ["wireless-matrix" "wireless-matrix-part"]
              :tile-kind :wireless-matrix}]
     :tile-ids ["wireless-matrix"]
     :capabilities [{:key :wireless-matrix
                     :interface IWirelessMatrix
                     :factory (fn [be _side] (matrix-capability/->WirelessMatrixImpl be))}]
     :containers {"wireless-matrix" matrix-logic/matrix-container-fns}
     :after register-matrix-multiblock!
     :network-handler matrix-handlers/register-network-handlers!
     :client-renderer 'cn.li.ac.block.wireless-matrix.render/init!}))
