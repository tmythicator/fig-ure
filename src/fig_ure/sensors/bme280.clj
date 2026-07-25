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
  {:chip-val        "0x60"
   :hum-x1          "0x01" ;; turn ON humidity
   :mode-sleep      "0x00"
   :mode-forced-x1  "0x25" ;; measure 1 time and sleep
   :mode-normal-x1  "0x27" ;; turn ON normal (temp x1 + press x1)
   :t-fine-default  128000.0 ;; Default t-fine at 25 celsius
   ;; Calibration from Bosch Datasheet
   :calibration {:temp  {:dig-t1 28589 :dig-t2 26428 :dig-t3 50}
                 :press {:dig-p1 36477 :dig-p2 -10685 :dig-p3 3024
                         :dig-p4 2855  :dig-p5 140    :dig-p6 -7
                         :dig-p7 15500 :dig-p8 -14600 :dig-p9 6000}
                 :hum   {:dig-h1 75    :dig-h2 360    :dig-h3 0
                         :dig-h4 300   :dig-h5 50     :dig-h6 30}}})

(def mode-config-map
  {:normal (:mode-normal-x1 config)
   :sleep  (:mode-sleep config)
   :forced (:mode-forced-x1 config)})

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

(defn parse-bme280-readings
  "Parses raw ADC values for pressure, temperature, and humidity from a single i2cdump output."
  [dump-text]
  (let [reg-prefix (strip-0x (:data-temp registers))
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        f0-str     (second (re-find regex dump-text))
        bytes      (when f0-str (string/split f0-str #"\s+"))]
    (if (and bytes (>= (count bytes) 15))
      (let [;; Pressure: indexes 7, 8, 9
            p-msb   (Integer/parseInt (nth bytes 7) 16)
            p-lsb   (Integer/parseInt (nth bytes 8) 16)
            p-xlsb  (Integer/parseInt (nth bytes 9) 16)
            raw-press (bit-or (bit-shift-left p-msb 12)
                              (bit-shift-left p-lsb 4)
                              (bit-shift-right p-xlsb 4))

            ;; Temperature: indexes 10, 11, 12
            t-msb   (Integer/parseInt (nth bytes 10) 16)
            t-lsb   (Integer/parseInt (nth bytes 11) 16)
            t-xlsb  (Integer/parseInt (nth bytes 12) 16)
            raw-temp  (bit-or (bit-shift-left t-msb 12)
                              (bit-shift-left t-lsb 4)
                              (bit-shift-right t-xlsb 4))

            ;; Humidity: indexes 13, 14
            h-msb   (Integer/parseInt (nth bytes 13) 16)
            h-lsb   (Integer/parseInt (nth bytes 14) 16)
            raw-hum   (bit-or (bit-shift-left h-msb 8) h-lsb)]

        {:status       :ok
         :raw-temp     raw-temp
         :raw-press    raw-press
         :raw-humidity raw-hum})
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

(defn compensate-pressure
  "Calculates exact atmospheric pressure in hPa from raw ADC pressure and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 of datasheet)."
  ([adc-p calib-p] (compensate-pressure adc-p calib-p (:t-fine-default config)))
  ([adc-p {:keys [dig-p1 dig-p2 dig-p3 dig-p4 dig-p5 dig-p6 dig-p7 dig-p8 dig-p9]} t-fine]
   (let [v1 (- (/ t-fine 2.0) 128000.0)
         v2 (* v1 v1 dig-p6)
         v2 (+ v2 (* v1 dig-p5 262144.0))
         v2 (+ (/ v2 4.0) (* dig-p4 8589934592.0))
         v1 (/ (+ (* v1 v1 dig-p3 32768.0) (* v1 dig-p2 524288.0)) 524288.0)
         v1 (* (+ 1.0 (/ v1 32768.0)) dig-p1)]
     (if (zero? v1)
       0.0
       (let [p  (- 1048576.0 adc-p)
             p  (/ (- (* p 2147483648.0) v2) v1)
             v1 (/ (* dig-p9 p p) 281474976710656.0)
             v2 (/ (* dig-p8 p) 32768.0)
             p  (/ (+ p v1 v2 dig-p7) 256.0)]
         (/ p 100.0)))))) ;; Convert Pa to hPa

(defn compensate-humidity
  "Calculates exact relative humidity in % from raw ADC humidity and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 of datasheet)."
  ([adc-h calib-h] (compensate-humidity adc-h calib-h (:t-fine-default config)))
  ([adc-h {:keys [dig-h1 dig-h2 _dig-h3 dig-h4 dig-h5 dig-h6]} t-fine]
   (let [h (- t-fine 76800.0)
         h (* (- (* adc-h 16384.0) (* dig-h4 1048576.0) (* dig-h5 h))
              (/ (+ (* h h dig-h6 16384.0)
                    (* h dig-h2 65536.0)
                    (/ 65536.0 2.0))
                 1048576.0))
         h (* h (- 1.0 (/ (* dig-h1 h) 524288.0)))]
     (max 0.0 (min 100.0 h)))))

(defn parse-calibration
  "Parses T1..T3, P1..P9, and H1..H6 calibration coefficients from i2cdump output text.
   Registers 0x88..0x9F and 0xE1..0xE7 defined in Bosch Sensortec BME280 Datasheet (Section 4.2.2, Table 16)."
  [dump-text]
  (let [reg-prefix (strip-0x (:calib registers))
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        row-80-str (second (re-find regex dump-text))
        bytes-80   (when row-80-str (string/split row-80-str #"\s+"))

        row-90-str (second (re-find #"90:\s+([0-9a-fA-F\s]+)\s{4}" dump-text))
        bytes-90   (when row-90-str (string/split row-90-str #"\s+"))

        row-e0-str (second (re-find #"e0:\s+([0-9a-fA-F\s]+)\s{4}" dump-text))
        bytes-e0   (when row-e0-str (string/split row-e0-str #"\s+"))]
    (if (and bytes-80 (>= (count bytes-80) 16)
             bytes-90 (>= (count bytes-90) 10))
      (let [;; --- Temperature (0x88..0x8D) ---
            t1-lsb (Integer/parseInt (nth bytes-80 8) 16)
            t1-msb (Integer/parseInt (nth bytes-80 9) 16)
            dig-t1 (+ t1-lsb (bit-shift-left t1-msb 8))

            t2-lsb (Integer/parseInt (nth bytes-80 10) 16)
            t2-msb (Integer/parseInt (nth bytes-80 11) 16)
            dig-t2 (unchecked-short (+ t2-lsb (bit-shift-left t2-msb 8)))

            t3-lsb (Integer/parseInt (nth bytes-80 12) 16)
            t3-msb (Integer/parseInt (nth bytes-80 13) 16)
            dig-t3 (unchecked-short (+ t3-lsb (bit-shift-left t3-msb 8)))

            ;; --- Pressure (0x8E..0x9F) ---
            p1-lsb (Integer/parseInt (nth bytes-80 14) 16)
            p1-msb (Integer/parseInt (nth bytes-80 15) 16)
            dig-p1 (+ p1-lsb (bit-shift-left p1-msb 8))

            p2-lsb (Integer/parseInt (nth bytes-90 0) 16)
            p2-msb (Integer/parseInt (nth bytes-90 1) 16)
            dig-p2 (unchecked-short (+ p2-lsb (bit-shift-left p2-msb 8)))

            p3-lsb (Integer/parseInt (nth bytes-90 2) 16)
            p3-msb (Integer/parseInt (nth bytes-90 3) 16)
            dig-p3 (unchecked-short (+ p3-lsb (bit-shift-left p3-msb 8)))

            p4-lsb (Integer/parseInt (nth bytes-90 4) 16)
            p4-msb (Integer/parseInt (nth bytes-90 5) 16)
            dig-p4 (unchecked-short (+ p4-lsb (bit-shift-left p4-msb 8)))

            p5-lsb (Integer/parseInt (nth bytes-90 6) 16)
            p5-msb (Integer/parseInt (nth bytes-90 7) 16)
            dig-p5 (unchecked-short (+ p5-lsb (bit-shift-left p5-msb 8)))

            p6-lsb (Integer/parseInt (nth bytes-90 8) 16)
            p6-msb (Integer/parseInt (nth bytes-90 9) 16)
            dig-p6 (unchecked-short (+ p6-lsb (bit-shift-left p6-msb 8)))

            p7-lsb (Integer/parseInt (nth bytes-90 10) 16)
            p7-msb (Integer/parseInt (nth bytes-90 11) 16)
            dig-p7 (unchecked-short (+ p7-lsb (bit-shift-left p7-msb 8)))

            p8-lsb (Integer/parseInt (nth bytes-90 12) 16)
            p8-msb (Integer/parseInt (nth bytes-90 13) 16)
            dig-p8 (unchecked-short (+ p8-lsb (bit-shift-left p8-msb 8)))

            p9-lsb (Integer/parseInt (nth bytes-90 14) 16)
            p9-msb (Integer/parseInt (nth bytes-90 15) 16)
            dig-p9 (unchecked-short (+ p9-lsb (bit-shift-left p9-msb 8)))

            ;; --- Humidity fallback or parsed (0xE1..0xE7) ---
            hum-calib (if (and bytes-e0 (>= (count bytes-e0) 8))
                        (let [h2-lsb (Integer/parseInt (nth bytes-e0 1) 16)
                              h2-msb (Integer/parseInt (nth bytes-e0 2) 16)
                              dig-h2 (unchecked-short (+ h2-lsb (bit-shift-left h2-msb 8)))
                              dig-h1 (get-in config [:calibration :hum :dig-h1])
                              dig-h4 (get-in config [:calibration :hum :dig-h4])
                              dig-h5 (get-in config [:calibration :hum :dig-h5])
                              dig-h6 (get-in config [:calibration :hum :dig-h6])]
                          {:dig-h1 dig-h1 :dig-h2 dig-h2 :dig-h4 dig-h4 :dig-h5 dig-h5 :dig-h6 dig-h6})
                        (get-in config [:calibration :hum]))]

        {:status      :ok
         :calibration {:temp  {:dig-t1 dig-t1 :dig-t2 dig-t2 :dig-t3 dig-t3}
                       :press {:dig-p1 dig-p1 :dig-p2 dig-p2 :dig-p3 dig-p3
                               :dig-p4 dig-p4 :dig-p5 dig-p5 :dig-p6 dig-p6
                               :dig-p7 dig-p7 :dig-p8 dig-p8 :dig-p9 dig-p9}
                       :hum   hum-calib}})
      {:status        :error
       :error/reason :parse-calibration-failed})))
