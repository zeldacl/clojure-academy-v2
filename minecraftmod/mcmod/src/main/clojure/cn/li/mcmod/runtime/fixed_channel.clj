(ns cn.li.mcmod.runtime.fixed-channel)
(import '(java.nio ByteBuffer ByteOrder))
(import '(java.nio.charset StandardCharsets))
(def ^:const protocol-version 1)
(def packet-types {:catalog-hello 1 :catalog-ack 2 :input-edge 3 :input-ack 4 :combat-feedback 5 :vfx-trigger 6 :vfx-spawn 7 :vfx-update 8 :vfx-destroy 9 :vfx-clear-owner 10 :vfx-snapshot 11})
(def reverse-packet-types (into {} (map (fn [[k v]] [v k]) packet-types)))
(defn- bounded-string-bytes [value max-length]
  (let [^String text (str value) ^bytes bytes (.getBytes text StandardCharsets/UTF_8)]
    (when (> (alength bytes) max-length) (throw (ex-info "protocol string exceeds bound" {:max max-length :value value}))) bytes))
(defn- put-string! [^ByteBuffer buffer value max-length]
  (let [^bytes bytes (bounded-string-bytes value max-length)] (.putShort buffer (short (alength bytes))) (.put buffer bytes) buffer))
(defn- get-string! [^ByteBuffer buffer max-length]
  (let [size (bit-and 0xffff (int (.getShort buffer)))]
    (when (> size max-length) (throw (ex-info "protocol string exceeds bound" {:max max-length :size size})))
    (let [^bytes bytes (byte-array size)] (.get buffer bytes) (String. bytes StandardCharsets/UTF_8))))
(defn encode-intent [{:keys [seq control-id edge choice client-tick] :as intent}]
  (when-not (and (integer? seq) (<= 0 seq 2147483647)) (throw (ex-info "invalid input seq" {:intent intent})))
  (when-not (and (integer? control-id) (<= 0 control-id 255)) (throw (ex-info "invalid control id" {:intent intent})))
  (when-not (contains? #{:press :release :abort} edge) (throw (ex-info "invalid input edge" {:intent intent})))
  (let [choice-tag (cond (nil? choice) 0 (integer? choice) 1 (string? choice) 2 :else -1)
        _ (when (= -1 choice-tag) (throw (ex-info "invalid input choice" {:intent intent})))
        ^ByteBuffer buffer (doto (ByteBuffer/allocate 64) (.order ByteOrder/BIG_ENDIAN) (.put (byte protocol-version)) (.put (byte (:input-edge packet-types))) (.putInt (int seq)) (.put (byte control-id)) (.put (byte ({:press 1 :release 2 :abort 3} edge))) (.put (byte choice-tag)) (.putInt (int (or client-tick 0))))]
    (case choice-tag 1 (.putInt buffer (int choice)) 2 (put-string! buffer choice 128) nil)
    (let [length (.position buffer) result (byte-array length)] (.flip buffer) (.get buffer result) result)))
(defn decode-intent [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        version (bit-and 0xff (int (.get buffer))) type (bit-and 0xff (int (.get buffer)))]
    (when-not (= protocol-version version) (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:input-edge packet-types) type) (throw (ex-info "packet is not an input edge" {:type type})))
    (let [seq (Integer/toUnsignedLong (.getInt buffer)) control-id (bit-and 0xff (int (.get buffer))) edge (get {1 :press 2 :release 3 :abort} (bit-and 0xff (int (.get buffer)))) choice-tag (bit-and 0xff (int (.get buffer))) client-tick (Integer/toUnsignedLong (.getInt buffer))]
      (when-not edge (throw (ex-info "invalid input edge tag" {})))
      {:type :input-edge :seq seq :control-id control-id :edge edge :choice (case choice-tag 0 nil 1 (.getInt buffer) 2 (get-string! buffer 128) (throw (ex-info "invalid choice tag" {:tag choice-tag}))) :client-tick client-tick})))
(defn frame [packet-type payload]
  (when-not (contains? packet-types packet-type) (throw (ex-info "unknown fixed packet type" {:packet-type packet-type})))
  (let [^bytes payload (byte-array payload) ^ByteBuffer buffer (doto (ByteBuffer/allocate (+ 4 (alength payload))) (.order ByteOrder/BIG_ENDIAN) (.put (byte protocol-version)) (.put (byte (get packet-types packet-type))) (.putShort (short (alength payload))) (.put payload)) result (byte-array (.position buffer))]
    (.flip buffer) (.get buffer result) result))

(defn- encode-catalog-payload [{:keys [schema-version content-hash] :as catalog}]
  (when-not (and (integer? schema-version) (<= 0 schema-version 65535))
    (throw (ex-info "invalid catalog schema version" {:catalog catalog})))
  (let [^bytes hash-bytes (bounded-string-bytes content-hash 128)
        ^ByteBuffer buffer (doto (ByteBuffer/allocate (+ 4 (alength hash-bytes)))
                             (.order ByteOrder/BIG_ENDIAN)
                             (.putShort (short schema-version))
                             (.putShort (short (alength hash-bytes)))
                             (.put hash-bytes))
        result (byte-array (.position buffer))]
    (.flip buffer) (.get buffer result) result))

(defn- decode-catalog-payload [^bytes payload]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))
        schema-version (bit-and 0xffff (int (.getShort buffer)))
        hash-length (bit-and 0xffff (int (.getShort buffer)))]
    (when (> hash-length 128)
      (throw (ex-info "catalog hash exceeds bound" {:length hash-length})))
    (when-not (= hash-length (.remaining buffer))
      (throw (ex-info "catalog hash length mismatch" {:declared hash-length :actual (.remaining buffer)})))
    (let [^bytes hash-bytes (byte-array hash-length)]
      (.get buffer hash-bytes)
      {:schema-version schema-version :content-hash (String. hash-bytes StandardCharsets/UTF_8)})))

(defn encode-catalog-hello [catalog]
  (frame :catalog-hello (encode-catalog-payload catalog)))

(defn decode-catalog-hello [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        version (bit-and 0xff (int (.get buffer)))
        type (bit-and 0xff (int (.get buffer)))
        payload-length (bit-and 0xffff (int (.getShort buffer)))]
    (when-not (= protocol-version version)
      (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:catalog-hello packet-types) type)
      (throw (ex-info "packet is not a catalog hello" {:type type})))
    (when-not (= payload-length (.remaining buffer))
      (throw (ex-info "catalog hello length mismatch" {:declared payload-length :actual (.remaining buffer)})))
    (let [payload (byte-array payload-length)]
      (.get buffer payload)
      (assoc (decode-catalog-payload payload) :type :catalog-hello))))

(defn encode-catalog-ack [{:keys [accepted? schema-version content-hash]}]
  (let [identity (encode-catalog-payload {:schema-version schema-version :content-hash content-hash})
        payload (byte-array (concat [(byte (if accepted? 1 0))] (seq identity)))]
    (frame :catalog-ack payload)))

(defn decode-catalog-ack [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        version (bit-and 0xff (int (.get buffer)))
        type (bit-and 0xff (int (.get buffer)))
        payload-length (bit-and 0xffff (int (.getShort buffer)))
        accepted-tag (bit-and 0xff (int (.get buffer)))]
    (when-not (= protocol-version version)
      (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:catalog-ack packet-types) type)
      (throw (ex-info "packet is not a catalog ack" {:type type})))
    (when-not (contains? #{0 1} accepted-tag)
      (throw (ex-info "invalid catalog ack tag" {:tag accepted-tag})))
    (when-not (= payload-length (inc (.remaining buffer)))
      (throw (ex-info "catalog ack length mismatch" {:declared payload-length :actual (inc (.remaining buffer))})))
    (let [payload (byte-array (.remaining buffer))]
      (.get buffer payload)
      (assoc (decode-catalog-payload payload)
             :type :catalog-ack
             :accepted? (= 1 accepted-tag)))))
