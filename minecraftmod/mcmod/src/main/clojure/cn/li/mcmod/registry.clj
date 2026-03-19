(ns cn.li.mcmod.registry)

;; 1. 定义一个简单的原子，存放待注册的条目
(def blocks (atom []))
(def items (atom []))

;; 2. 提供给 ac 使用的声明函数
(defn register-block! [id supplier]
  (swap! blocks conj {:id id :supplier supplier}))

(defn register-item! [id supplier]
  (swap! items conj {:id id :supplier supplier}))

;; 3. 一个特殊的函数，用于在版本子项目中获取这些条目
(defn get-all-blocks [] @blocks)
(defn get-all-items [] @items)