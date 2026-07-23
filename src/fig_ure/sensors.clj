(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :refer [split]]
            [integrant.core :as ig]))

(def ^:private bme280-i2c-addr "0x77")
(def ^:private bme280-chip-id-reg "d0")
(def ^:private bme280-expected-chip-id "60")
(def ^:private bme280-temperature-reg "f0")

(defn- fetch-i2cdump
  "Executes i2cdump command for specified address and returns result map."
  [addr]
  (let [result (sh "i2cdump" "-y" "1" addr)]
    (if (zero? (:exit result))
      {:status :ok :out (:out result)}
      {:status        :error
       :error/reason  :i2c-read-failed
       :error/message (:err result)})))

(defn format-reading
  "Formats a raw sensor reading into the internal telemetry map structure."
  [sensor-id raw-val unit]
  {:sensor/id        sensor-id
   :sensor/value     raw-val
   :sensor/unit      unit
   :sensor/timestamp (System/currentTimeMillis)})

(defn valid-percent-reading?
  "Check if a percent reading is valid (number and within reasonable range)."
  [reading]
  (let [val  (:sensor/value reading)
        unit (:sensor/unit reading)]
    (boolean (and (= unit :percent)
                  (number? val)
                  (<= 0 val 100.0)))))

(defn calculate-average-percent-value
  "Calculates average value over the readings from one sensor (percent unit)."
  [readings]
  (let [values (->> readings
                    (filter valid-percent-reading?)
                    (map :sensor/value))]
    (if (seq values)
      (/ (reduce + values) (count values))
      0.0)))

(defn parse-i2cdump-chip-id
  "Parses raw i2cdump output text to get the BME280 chip ID."
  [dump-text]
  (let [regex   (re-pattern (str bme280-chip-id-reg ":\\s+([0-9a-fA-F]{2})"))
        chip-id (second (re-find regex dump-text))]
    (if chip-id
      {:status         :ok
       :bme280/chip-id (str "0x" chip-id)
       :bme280/valid?  (= chip-id bme280-expected-chip-id)}
      {:status        :error
       :error/reason :parse-failed})))

(defn read-bme280-chip-id
  "Reads the BME280 chip ID register (0xD0) via i2cdump over I2C bus 1."
  []
  (let [dump (fetch-i2cdump bme280-i2c-addr)]
    (if (= :ok (:status dump))
      (parse-i2cdump-chip-id (:out dump))
      dump)))

(defn parse-bme280-temperature
  "Parses raw ADC temperature bytes from i2cdump output text."
  [dump-text]
  (let [regex   (re-pattern (str bme280-temperature-reg ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        f0-str (second (re-find regex dump-text))
        bytes (split f0-str #"\s+")]
    (if (and bytes (>= (count bytes) 13))
      (let [msb (Integer/parseInt (nth bytes 10) 16)
            lsb (Integer/parseInt (nth bytes 11) 16)
            xlsb (Integer/parseInt (nth bytes 12) 16)
            msb-shifted (bit-shift-left msb 12)
            lsb-shifted (bit-shift-left lsb 4)
            xlsb-shifted (bit-shift-right xlsb 4)
            raw-val (bit-or msb-shifted lsb-shifted xlsb-shifted)]
        {:status :ok
         :reading (format-reading :bme280-temperature raw-val :raw-adc)})
      {:status :error
       :error/reason :parse-failed})))

(comment
  (read-bme280-temperature)
  (parse-bme280-temperature  (slurp "test/fixtures/bme280_i2cdump.txt")))

(defn read-bme280-temperature
  "Reads the temperature info from BME280"
  []
  (let [dump (fetch-i2cdump bme280-i2c-addr)]
    (if (= :ok (:status dump))
      (parse-bme280-temperature (:out dump))
      dump)))

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "[Sensors] Initializing BME280 sensor reader..." config)
  (let [handshake (read-bme280-chip-id)]
    (println "[Sensors] BME280 Handshake Status:" handshake)
    {:status :ready
     :bme280 handshake}))

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "[Sensors] Halting sensor reader..." state))

(comment
  ;; Interactive REPL scratchpad
  (read-bme280-chip-id))