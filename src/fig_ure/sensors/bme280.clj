(ns fig-ure.sensors.bme280
  "Hardware I/O driver and pure text parsers for Bosch BME280 I2C sensor."
  (:require [clojure.string :as string]))

(def i2c-addr "0x77")
(def registers
  "BME280 register addresses."
  {:chip-id "0xd0"
   :calib "0x80"
   :ctrl-hum "0xf2"
   :ctrl-meas "0xf4"
   :data-temp "0xf0"
   :data-press "0xf7"
   :data-hum "0xfd"})

(def config
  "BME280 configuration and expected vals."
  {:chip-val "0x60"
   :hum-x1 "0x01" ;; turn ON humidity
   :mode-sleep "0x00"
   :mode-forced-x1 "0x25" ;; measure 1 time and sleep
   :mode-normal-x1 "0x27" ;; turn ON normal (temp x1 + press x1)
   ;; Calibration from Bosch Datasheet
   :calibration {:dig-t1 28589
                 :dig-t2 26428
                 :dig-t3 50}})

(defn parse-calibration
  "Parses T1, T2, T3 calibration coefficients from i2cdump output text."
  [dump-text]
  (let [regex   (re-pattern "80:\\s+([0-9a-fA-F\\s]+)\\s{4}")
        row-str (second (re-find regex dump-text))
        bytes   (when row-str (string/split row-str #"\s+"))]
    (if (and bytes (>= (count bytes) 14))
      (let [t1-lsb (Integer/parseInt (nth bytes 8) 16)
            t1-msb (Integer/parseInt (nth bytes 9) 16)
            dig-t1 (+ t1-lsb (bit-shift-left t1-msb 8))

            t2-lsb (Integer/parseInt (nth bytes 10) 16)
            t2-msb (Integer/parseInt (nth bytes 11) 16)
            dig-t2 (short (+ t2-lsb (bit-shift-left t2-msb 8)))

            t3-lsb (Integer/parseInt (nth bytes 12) 16)
            t3-msb (Integer/parseInt (nth bytes 13) 16)
            dig-t3 (short (+ t3-lsb (bit-shift-left t3-msb 8)))]
        {:status      :ok
         :calibration {:dig-t1 dig-t1
                       :dig-t2 dig-t2
                       :dig-t3 dig-t3}})
      {:status :error
       :error/reason
       :parse-calibration-failed})))


(defn- strip-0x [s]
  (if (string/starts-with? s "0x")
    (subs s 2)
    s))

(defn decode-mode
  "Decodes hex string to mode keyword."
  [hex-str]
  (case hex-str
    "0x27" :normal
    "0x00" :sleep
    "0x25" :forced
    :unknown))

(defn decode-chip-id
  "Decodes raw hex chip ID string and validates against config."
  [hex-str]
  {:bme280/chip-id hex-str
   :bme280/valid?  (= hex-str (:chip-val config))})

(defn parse-temperature
  "Parses raw ADC temperature bytes from i2cdump output text."
  [dump-text]
  (let [reg-prefix (strip-0x (:data-temp registers))
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        f0-str     (second (re-find regex dump-text))
        bytes      (when f0-str (string/split f0-str #"\s+"))]
    (if (and bytes (>= (count bytes) 13))
      (let [msb          (Integer/parseInt (nth bytes 10) 16)
            lsb          (Integer/parseInt (nth bytes 11) 16)
            xlsb         (Integer/parseInt (nth bytes 12) 16)
            msb-shifted  (bit-shift-left msb 12)
            lsb-shifted  (bit-shift-left lsb 4)
            xlsb-shifted (bit-shift-right xlsb 4)
            raw-val      (bit-or msb-shifted lsb-shifted xlsb-shifted)]
        {:status  :ok
         :reading raw-val})
      {:status        :error
       :error/reason :parse-failed})))

(defn compensate-temperature
  "Calculates exact temperature in Celsius from raw ADC value and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 of datasheet)."
  [adc-t {:keys [dig-t1 dig-t2 dig-t3]}]
  (let [v1     (* (- (/ adc-t 16384.0) (/ dig-t1 1024.0)) dig-t2)
        v2-tmp (- (/ adc-t 131072.0) (/ dig-t1 8192.0))
        v2     (* v2-tmp v2-tmp dig-t3)
        t-fine (+ v1 v2)]
    (/ t-fine 5120.0)))

(defn parse-calibration
  "Parses T1, T2, T3 calibration coefficients from i2cdump output text.
   Registers 0x88..0x8D defined in Bosch Sensortec BME280 Datasheet (Section 4.2.2, Table 16)."
  [dump-text]
  (let [reg-prefix (strip-0x (:calib registers))
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        row-str    (second (re-find regex dump-text))
        bytes      (when row-str (string/split row-str #"\s+"))]
    (if (and bytes (>= (count bytes) 14))
      (let [t1-lsb (Integer/parseInt (nth bytes 8) 16)
            t1-msb (Integer/parseInt (nth bytes 9) 16)
            dig-t1 (+ t1-lsb (bit-shift-left t1-msb 8))

            t2-lsb (Integer/parseInt (nth bytes 10) 16)
            t2-msb (Integer/parseInt (nth bytes 11) 16)
            dig-t2 (short (+ t2-lsb (bit-shift-left t2-msb 8)))

            t3-lsb (Integer/parseInt (nth bytes 12) 16)
            t3-msb (Integer/parseInt (nth bytes 13) 16)
            dig-t3 (short (+ t3-lsb (bit-shift-left t3-msb 8)))]
        {:status      :ok
         :calibration {:dig-t1 dig-t1
                       :dig-t2 dig-t2
                       :dig-t3 dig-t3}})
      {:status        :error
       :error/reason :parse-calibration-failed})))
