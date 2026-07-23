(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require [clojure.java.shell :refer [sh]]
            [fig-ure.sensors.bme280 :as bme280]
            [integrant.core :as ig]))

(defn fetch-i2cdump
  "Executes i2cdump command for specified address and bus (defaults to bus '1')."
  ([addr] (fetch-i2cdump "1" addr))
  ([bus addr]
   (let [result (sh "i2cdump" "-y" bus addr)]
     (if (zero? (:exit result))
       {:status :ok :out (:out result)}
       {:status        :error
        :error/reason  :i2c-read-failed
        :error/message (:err result)}))))

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

(defn read-bme280-chip-id
  "Reads BME280 chip ID register via shared fetch-i2cdump helper."
  ([] (read-bme280-chip-id "1"))
  ([bus]
   (let [dump (fetch-i2cdump bus bme280/i2c-addr)]
     (if (= :ok (:status dump))
       (bme280/parse-chip-id (:out dump))
       dump))))

(defn read-bme280-temperature
  "Reads raw ADC temperature reading from BME280 driver."
  ([] (read-bme280-temperature "1"))
  ([bus]
   (let [dump (fetch-i2cdump bus bme280/i2c-addr)]
     (if (= :ok (:status dump))
       (bme280/parse-temperature (:out dump) format-reading)
       dump))))

;; -----------------------------------------------------------------------------
;; Integrant Lifecycle Methods
;; -----------------------------------------------------------------------------

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "[Sensors] Initializing BME280 sensor reader..." config)
  (let [bus       (or (:i2c-bus config) "1")
        handshake (read-bme280-chip-id bus)]
    (println "[Sensors] BME280 Handshake Status:" handshake)
    {:status   :ready
     :i2c-bus  bus
     :bme280   handshake}))

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "[Sensors] Halting sensor reader..." state))

(comment
  ;; Interactive REPL scratchpad
  (read-bme280-temperature)
  (read-bme280-chip-id))