(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require [clojure.java.shell :refer [sh]]
            [integrant.core :as ig]))

(def ^:private bme280-i2c-addr "0x77")
(def ^:private bme280-chip-id-reg "d0")
(def ^:private bme280-expected-chip-id "60")

(defn valid-percent-reading?
  "Check if a percent reading is valid (number and within reasonable range)."
  [reading]
  (let [val (:sensor/value reading)
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
  "Parses i2cdump to get the chip id."
  [dump]
  (let [regex (re-pattern (str bme280-chip-id-reg ":\\s+([0-9a-fA-F]{2})"))
        chip-id (second (re-find regex dump))]
    (if chip-id
      {:status :ok
       :chip-id (str "0x" chip-id)
       :valid? (= chip-id bme280-expected-chip-id)}
      {:status :error
       :reason :parse-failed})))

(defn read-bme280-chip-id
  "Reads the BME280 chip ID register (0xD0) via i2cdump."
  []
  (let [result (sh "i2cdump" "-y" "1" bme280-i2c-addr)]
    (if (zero? (:exit result))
      (parse-i2cdump-chip-id result)
      {:status :error
       :msg (:err result)
       :reson :i2c-read-failed})))

(defn format-reading
  "Formats a raw sensor reading into the internal telemetry map structure."
  [sensor-id raw-val unit]
  {:sensor/id sensor-id
   :sensor/value raw-val
   :sensor/unit unit
   :sensor/timestamp (System/currentTimeMillis)})

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "Initializing sensor reader..." config)
  ;; Component state returned to Integrant

  {:status :ready})

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "Halting sensor reader..." state))

(comment
  ;; Interactive REPL scratchpad
  (:exit {:exit 33})
  (format-reading :soil-ham 12.3 :percent))