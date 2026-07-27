(ns fig-ure.sensors.seesaw
  "Driver for Adafruit STEMMA Soil Moisture & Temp Sensor via ATSAMD10 I2C protocol."
  (:require [clojure.string :as string]
            [fig-ure.util :as util]))

(def i2c-addr "0x36")

(def base-registers
  "Seesaw subsystem base registers."
  {:status 0x00
   :touch  0x0F})

(def function-offsets
  "Seesaw function register offsets."
  {:hardware-id 0x01
   :temperature 0x04
   :moisture    0x10})

(def config
  "Adafruit Seesaw default configuration and expected hardware ID."
  {:hardware-id-val "0x36"
   :default-bus     "1"})

(defn decode-hardware-id
  "Decodes raw hardware ID from Seesaw chip and checks if valid (0x36)."
  [raw-hex]
  (let [clean-val (util/strip-0x (string/trim (or raw-hex "")))]
    {:seesaw/hardware-id (str "0x" clean-val)
     :seesaw/valid?       (= clean-val (util/strip-0x (:hardware-id-val config)))}))

(defn- parse-raw-moisture-bytes
  "Parses 2-byte hex strings into a 16-bit uint16 moisture value."
  [msb-str lsb-str]
  (let [parsed (mapv util/parse-hex [msb-str lsb-str])]
    (when (every? some? parsed)
      (let [[msb lsb] parsed]
        (bit-or (bit-shift-left msb 8) lsb)))))

(defn parse-soil-moisture
  "Parses 2-byte response into soil moisture reading map."
  [byte-strs]
  (if (and (coll? byte-strs) (= 2 (count byte-strs)))
    (if-let [val (parse-raw-moisture-bytes (first byte-strs) (second byte-strs))]
      {:status   :ok
       :moisture val}
      {:status        :error
       :error/reason  :invalid-bytes})
    {:status        :error
     :error/reason  :invalid-response-length}))

(defn- parse-raw-temperature-bytes
  [b1 b2 b3 b4]
  (let [parsed (mapv util/parse-hex [b1 b2 b3 b4])]
    (when (every? some? parsed)
      (let [[b1-hex b2-hex b3-hex b4-hex] parsed
            raw-int (bit-or
                     (bit-shift-left b1-hex 24)
                     (bit-shift-left b2-hex 16)
                     (bit-shift-left b3-hex 8)
                     b4-hex)
            two-pow-16 65536.0]
        (/ raw-int two-pow-16)))))

(defn parse-soil-temperature
  "Parses 4-byte response into soil temperature reading map."
  [byte-strs]
  (if (and (coll? byte-strs) (= 4 (count byte-strs)))
    (if-let [val (parse-raw-temperature-bytes (nth byte-strs 0)
                                              (nth byte-strs 1)
                                              (nth byte-strs 2)
                                              (nth byte-strs 3))]
      {:status      :ok
       :temperature val}
      {:status        :error
       :error/reason  :invalid-bytes})
    {:status        :error
     :error/reason  :invalid-response-length}))
