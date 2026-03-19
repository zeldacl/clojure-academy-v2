
(ns cn.li.mcmod.core
  (:import [cn.li.mcmod IPlatform]))

;; 这个变量将在具体版本（如 forge-1.20.1）的入口处被初始化
(def ^:dynamic ^IPlatform *api* nil)

;; 快捷函数：供 ac 模块调用
(defn set-block! [level x y z state]
  (.setBlock (.block *api*) level x y z state))

(defn get-item-energy [stack]
  (.getLong (.item *api*) stack "energy"))


;; (ns com.example.mod.ac.logic
;;   (:require [com.example.mod.mcmod.core :as mc]))
;; 
;; (defn charge-machine [player level x y z]
;;   (if-not (.isClientSide mc/*api* level)
;;     (let [be (mc/get-block-entity! level x y z)]
;;       ;; 处理逻辑...
;;       (mc/send-message! player "Machine Charged!"))))
