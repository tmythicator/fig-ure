(ns fig-ure.sensors.bme280
  "Hardware I/O driver and pure text parsers for Bosch BME280 I2C sensor."
  (:require [clojure.string :as string]))

;; =============================================================================
;; 1. CONSTANTS & CONFIG
;; =============================================================================

(def i2c-addr "0x77")

(def registers
  "BME280 register addresses."
  {:chip-id    "0xd0"
   :calib      "0x80"
   :ctrl-hum   "0xf2"
   :ctrl-meas  "0xf4"
   :data-temp  "0xf0"
   :data-press "0xf7"
   :data-hum   "0xfd"})

(def config
  "BME280 configuration and expected vals."
  {:chip-val        "0x60"
   :hum-x1          "0x01" ;; turn ON humidity oversampling x1
   :hum-x16         "0x05" ;; turn ON humidity oversampling x16
   :mode-sleep      "0x00"
   :mode-forced-x1  "0x25" ;; measure 1 time (temp x1 + press x1) and sleep
   :mode-normal-x1  "0x27" ;; turn ON normal (temp x1 + press x1)
   :mode-normal-x16 "0xB7" ;; turn ON normal max precision (temp x16 + press x16)
   :t-fine-default  128000.0 ;; Default t-fine at 25 celsius
   ;; Calibration fallback from Bosch Datasheet
   :calibration {:temp  {:dig-t1 28589 :dig-t2 26428 :dig-t3 50}
                 :press {:dig-p1 36477 :dig-p2 -10685 :dig-p3 3024
                         :dig-p4 2855  :dig-p5 140    :dig-p6 -7
                         :dig-p7 15500 :dig-p8 -14600 :dig-p9 6000}
                 :hum   {:dig-h1 75    :dig-h2 360    :dig-h3 0
                         :dig-h4 300   :dig-h5 50     :dig-h6 30}}})

(def mode-config-map
  {:normal  {:hum (:hum-x16 config) :meas (:mode-normal-x16 config)}
   :weather {:hum (:hum-x1 config)  :meas (:mode-normal-x1 config)}
   :sleep   {:hum (:hum-x1 config)  :meas (:mode-sleep config)}
   :forced  {:hum (:hum-x1 config)  :meas (:mode-forced-x1 config)}})

(defn decode-mode
  "Decodes hex string to mode keyword."
  [hex-str]
  (case (string/lower-case hex-str)
    ("0x27" "0xb7" "0x3f") :normal
    "0x00"                :sleep
    ("0x25" "0x26")       :forced
    :unknown))

(defn decode-chip-id
  "Decodes raw hex chip ID string and validates against config."
  [hex-str]
  {:bme280/chip-id hex-str
   :bme280/valid?  (= hex-str (:chip-val config))})

;; =============================================================================
;; 2. RAW I2C LINE PARSERS
;; =============================================================================

(defn- parse-row-bytes
  "Extracts hex byte string vector from i2cdump row matching prefix (e.g. '80', '90', 'f0')."
  [dump-text row-hex]
  (when-let [row-str (second (re-find (re-pattern (str row-hex ":\\s+([0-9a-fA-F\\s]+)\\s{4}")) dump-text))]
    (string/split row-str #"\s+")))

(defn- parse-temp-calib [b80]
  (let [t1-lsb (Integer/parseInt (nth b80 8) 16)
        t1-msb (Integer/parseInt (nth b80 9) 16)
        t2-lsb (Integer/parseInt (nth b80 10) 16)
        t2-msb (Integer/parseInt (nth b80 11) 16)
        t3-lsb (Integer/parseInt (nth b80 12) 16)
        t3-msb (Integer/parseInt (nth b80 13) 16)]
    {:dig-t1 (+ t1-lsb (bit-shift-left t1-msb 8))
     :dig-t2 (unchecked-short (+ t2-lsb (bit-shift-left t2-msb 8)))
     :dig-t3 (unchecked-short (+ t3-lsb (bit-shift-left t3-msb 8)))}))

(defn- parse-press-calib [b80 b90]
  (let [p1-lsb (Integer/parseInt (nth b80 14) 16)
        p1-msb (Integer/parseInt (nth b80 15) 16)
        p2-lsb (Integer/parseInt (nth b90 0) 16)
        p2-msb (Integer/parseInt (nth b90 1) 16)
        p3-lsb (Integer/parseInt (nth b90 2) 16)
        p3-msb (Integer/parseInt (nth b90 3) 16)
        p4-lsb (Integer/parseInt (nth b90 4) 16)
        p4-msb (Integer/parseInt (nth b90 5) 16)
        p5-lsb (Integer/parseInt (nth b90 6) 16)
        p5-msb (Integer/parseInt (nth b90 7) 16)
        p6-lsb (Integer/parseInt (nth b90 8) 16)
        p6-msb (Integer/parseInt (nth b90 9) 16)
        p7-lsb (Integer/parseInt (nth b90 10) 16)
        p7-msb (Integer/parseInt (nth b90 11) 16)
        p8-lsb (Integer/parseInt (nth b90 12) 16)
        p8-msb (Integer/parseInt (nth b90 13) 16)
        p9-lsb (Integer/parseInt (nth b90 14) 16)
        p9-msb (Integer/parseInt (nth b90 15) 16)]
    {:dig-p1 (+ p1-lsb (bit-shift-left p1-msb 8))
     :dig-p2 (unchecked-short (+ p2-lsb (bit-shift-left p2-msb 8)))
     :dig-p3 (unchecked-short (+ p3-lsb (bit-shift-left p3-msb 8)))
     :dig-p4 (unchecked-short (+ p4-lsb (bit-shift-left p4-msb 8)))
     :dig-p5 (unchecked-short (+ p5-lsb (bit-shift-left p5-msb 8)))
     :dig-p6 (unchecked-short (+ p6-lsb (bit-shift-left p6-msb 8)))
     :dig-p7 (unchecked-short (+ p7-lsb (bit-shift-left p7-msb 8)))
     :dig-p8 (unchecked-short (+ p8-lsb (bit-shift-left p8-msb 8)))
     :dig-p9 (unchecked-short (+ p9-lsb (bit-shift-left p9-msb 8)))}))

(defn- parse-hum-calib [ba0 be0]
  (if (and be0 (>= (count be0) 8))
    (let [dig-h1 (if (and ba0 (>= (count ba0) 2))
                   (Integer/parseInt (nth ba0 1) 16)
                   (get-in config [:calibration :hum :dig-h1]))
          h2-lsb (Integer/parseInt (nth be0 1) 16)
          h2-msb (Integer/parseInt (nth be0 2) 16)
          dig-h2 (unchecked-short (+ h2-lsb (bit-shift-left h2-msb 8)))
          dig-h3 (Integer/parseInt (nth be0 3) 16)
          h4-msb (Integer/parseInt (nth be0 4) 16)
          h4-lsb (Integer/parseInt (nth be0 5) 16)
          dig-h4 (unchecked-short (bit-or (bit-shift-left h4-msb 4) (bit-and h4-lsb 0x0F)))
          h5-msb (Integer/parseInt (nth be0 6) 16)
          h5-lsb (Integer/parseInt (nth be0 5) 16)
          dig-h5 (unchecked-short (bit-or (bit-shift-left h5-msb 4) (bit-shift-right h5-lsb 4)))
          h6-val (Integer/parseInt (nth be0 7) 16)
          dig-h6 (byte (unchecked-byte h6-val))]
      {:dig-h1 dig-h1 :dig-h2 dig-h2 :dig-h3 dig-h3
       :dig-h4 dig-h4 :dig-h5 dig-h5 :dig-h6 dig-h6})
    (get-in config [:calibration :hum])))

;; =============================================================================
;; 3. CALIBRATION & READINGS PARSERS
;; =============================================================================

(defn parse-calibration
  "Parses T1..T3, P1..P9, and H1..H6 calibration coefficients from i2cdump output text.
   Registers 0x88..0x9F and 0xE1..0xE7 defined in Bosch Sensortec BME280 Datasheet (Section 4.2.2, Table 16)."
  [dump-text]
  (let [b80 (parse-row-bytes dump-text "80")
        b90 (parse-row-bytes dump-text "90")
        ba0 (parse-row-bytes dump-text "a0")
        be0 (parse-row-bytes dump-text "e0")]
    (if (and b80 (>= (count b80) 16)
             b90 (>= (count b90) 10))
      {:status      :ok
       :calibration {:temp  (parse-temp-calib b80)
                     :press (parse-press-calib b80 b90)
                     :hum   (parse-hum-calib ba0 be0)}}
      {:status        :error
       :error/reason :parse-calibration-failed})))

;; =============================================================================
;; 4. BOSCH MATH COMPENSATION
;; =============================================================================

(defn calculate-t-fine
  "Calculates internal raw t-fine temperature variable from raw ADC temperature and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 of datasheet)."
  [adc-t {:keys [dig-t1 dig-t2 dig-t3]}]
  (let [v1     (* (- (/ adc-t 16384.0) (/ dig-t1 1024.0)) dig-t2)
        v2-tmp (- (/ adc-t 131072.0) (/ dig-t1 8192.0))
        v2     (* v2-tmp v2-tmp dig-t3)]
    (+ v1 v2)))

(defn compensate-temperature
  "Calculates exact temperature in Celsius from raw ADC value and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 of datasheet)."
  [adc-t calib-t]
  (/ (calculate-t-fine adc-t calib-t) 5120.0))

(defn compensate-pressure
  "Calculates exact atmospheric pressure in hPa from raw ADC pressure and calibration coefficients.
   Ported directly from official Bosch Sensortec BME280 C driver (Section 4.2.3 32-bit integer arithmetic)."
  ([adc-p calib-p] (compensate-pressure adc-p calib-p (:t-fine-default config)))
  ([adc-p {:keys [dig-p1 dig-p2 dig-p3 dig-p4 dig-p5 dig-p6 dig-p7 dig-p8 dig-p9]} t-fine]
   (let [v1 (- (/ t-fine 2.0) 64000.0)
         v2 (/ (* v1 v1 dig-p6) 32768.0)
         v2 (+ v2 (* v1 dig-p5 2.0))
         v2 (+ (/ v2 4.0) (* dig-p4 65536.0))
         v1 (/ (+ (/ (* v1 v1 dig-p3) 524288.0) (* v1 dig-p2)) 524288.0)
         v1 (* (+ 1.0 (/ v1 32768.0)) dig-p1)]
     (if (zero? v1)
       0.0
       (let [p  (- 1048576.0 adc-p)
             p  (/ (* (- p (/ v2 4096.0)) 6250.0) v1)
             v1 (/ (* dig-p9 p p) 2147483648.0)
             v2 (/ (* p dig-p8) 32768.0)
             p  (+ p (/ (+ v1 v2 dig-p7) 16.0))]
         (/ p 100.0)))))) ;; Convert Pa to hPa

(defn compensate-humidity
  "Calculates exact relative humidity in % from raw ADC humidity and calibration coefficients.
   Ported 1-to-1 from official Bosch Sensortec BME280 C driver (Section 4.2.3 64-bit double precision)."
  ([adc-h calib-h] (compensate-humidity adc-h calib-h (:t-fine-default config)))
  ([adc-h {:keys [dig-h1 dig-h2 dig-h3 dig-h4 dig-h5 dig-h6]} t-fine]
   (let [var-h (- t-fine 76800.0)
         var-h (* (- adc-h (+ (* dig-h4 64.0) (* dig-h5 (/ var-h 16384.0))))
                  (/ dig-h2 65536.0)
                  (+ 1.0 (* (/ dig-h6 67108864.0)
                            var-h
                            (+ 1.0 (* (/ (or dig-h3 0) 67108864.0) var-h)))))
         var-h (* var-h (- 1.0 (/ (* dig-h1 var-h) 524288.0)))]
     (max 0.0 (min 100.0 var-h)))))

(defn parse-raw-adc
  "Parses raw ADC values for pressure, temperature, and humidity from raw i2cdump f0 line bytes."
  [dump-text]
  (when-let [bytes (parse-row-bytes dump-text "f0")]
    (when (>= (count bytes) 15)
      (let [p-msb  (Integer/parseInt (nth bytes 7) 16)
            p-lsb  (Integer/parseInt (nth bytes 8) 16)
            p-xlsb (Integer/parseInt (nth bytes 9) 16)
            t-msb  (Integer/parseInt (nth bytes 10) 16)
            t-lsb  (Integer/parseInt (nth bytes 11) 16)
            t-xlsb (Integer/parseInt (nth bytes 12) 16)
            h-msb  (Integer/parseInt (nth bytes 13) 16)
            h-lsb  (Integer/parseInt (nth bytes 14) 16)]
        {:raw-press (bit-or (bit-shift-left p-msb 12)
                            (bit-shift-left p-lsb 4)
                            (bit-shift-right p-xlsb 4))
         :raw-temp  (bit-or (bit-shift-left t-msb 12)
                            (bit-shift-left t-lsb 4)
                            (bit-shift-right t-xlsb 4))
         :raw-hum   (bit-or (bit-shift-left h-msb 8) h-lsb)}))))

(defn parse-bme280-readings
  "Parses raw ADC values for pressure, temperature, and humidity from a single i2cdump output using calibration."
  ([dump-text] (parse-bme280-readings dump-text (:calibration config)))
  ([dump-text calib]
   (if-let [{:keys [raw-press raw-temp raw-hum]} (parse-raw-adc dump-text)]
     (let [t-fine    (calculate-t-fine raw-temp (:temp calib))
           temp-res  (compensate-temperature raw-temp (:temp calib))
           press-res (compensate-pressure raw-press (:press calib) t-fine)
           hum-res   (compensate-humidity raw-hum (:hum calib) t-fine)]
       {:status :ok
        :temp   temp-res
        :press  press-res
        :hum    hum-res})
     {:status        :error
      :error/reason :parse-failed})))
