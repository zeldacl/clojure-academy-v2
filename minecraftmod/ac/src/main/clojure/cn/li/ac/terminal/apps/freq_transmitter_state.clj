(ns cn.li.ac.terminal.apps.freq-transmitter-state
  "Pure state machine for Frequency Transmitter interaction flow."
  (:require [clojure.string :as str]))

(def default-auth-timeout-ms 20000)
(def default-transfer-timeout-ms 3000)

(defn initial-state
  ([] (initial-state {}))
  ([{:keys [now-ms]
     :or {now-ms 0}}]
   {:phase :intro
    :context {}
    :status nil
    :updated-at now-ms
    :deadline-ms nil}))

(defn- with-phase
  [state phase now-ms deadline-ms status context]
  (-> state
      (assoc :phase phase
             :updated-at now-ms
             :deadline-ms deadline-ms
             :status status)
      (update :context merge context)))

(defn tick-timeout
  [state now-ms]
  (if (and (:deadline-ms state)
           (<= (:deadline-ms state) now-ms))
    (with-phase state :intro now-ms nil :timeout {:timeout-at now-ms})
    state))

(defn choose-matrix
  [state matrix-pos now-ms]
  (with-phase state :auth-matrix now-ms (+ now-ms default-auth-timeout-ms) nil {:matrix-pos matrix-pos}))

(defn choose-node
  [state node-pos now-ms]
  (with-phase state :auth-node now-ms (+ now-ms default-auth-timeout-ms) nil {:node-pos node-pos}))

(defn auth-ok
  [state now-ms]
  (case (:phase state)
    :auth-matrix
    (with-phase state :link-node now-ms (+ now-ms default-transfer-timeout-ms) :auth-ok {})

    :auth-node
    (with-phase state :link-user now-ms (+ now-ms default-transfer-timeout-ms) :auth-ok {})

    state))

(defn auth-failed
  [state now-ms]
  (with-phase state :intro now-ms nil :bad-password {}))

(defn link-ok
  [state now-ms]
  (-> state
      (with-phase :intro now-ms nil :linked {})
      (assoc :context {})))

(defn link-failed
  [state now-ms reason]
  (with-phase state :intro now-ms nil reason {}))

(defn intro-lines
  [state]
  (let [status (:status state)]
    (cond-> ["Frequency Transmitter"
             ""
             "流程：选择目标 -> 鉴权 -> 执行连接"
             "Matrix: 右键矩阵并输入密码后连接 Node"
             "Node: 右键节点并输入密码后连接 Generator/Receiver"]
      (= status :timeout) (conj "" "上一步已超时，请重试。")
      (= status :bad-password) (conj "" "密码错误。")
      (= status :linked) (conj "" "连接成功。")
      (and (string? (some-> status name)) (str/starts-with? (name status) "link-"))
      (conj "" "连接失败，请检查范围、容量与目标类型。"))))

