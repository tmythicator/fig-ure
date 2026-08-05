(ns fig-ure.sensors.seesaw-test
  (:require [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors.seesaw :as seesaw]
            [matcher-combinators.test :refer [match?]]))

(deftest decode-hardware-id-test
  (testing "decodes valid Adafruit Seesaw hardware ID (0x36)"
    (is (match? {:seesaw/hardware-id "0x36"
                 :seesaw/valid?       true}
                (seesaw/decode-hardware-id "0x36"))))

  (testing "invalidates mismatched hardware ID"
    (is (match? {:seesaw/hardware-id "0x99"
                 :seesaw/valid?       false}
                (seesaw/decode-hardware-id "0x99")))))

(deftest parse-soil-moisture-test
  (testing "parses 2-byte response into soil moisture uint16 reading"
    (is (= {:status   :ok
            :moisture 500}
           (seesaw/parse-soil-moisture ["01" "f4"])))
    (is (= {:status   :ok
            :moisture 321}
           (seesaw/parse-soil-moisture ["0x01" "0x41"]))))

  (testing "handles invalid response length or corrupt bytes gracefully"
    (is (match? {:status       :error
                 :error/reason :invalid-response-length}
                (seesaw/parse-soil-moisture ["01"])))
    (is (match? {:status       :error
                 :error/reason :invalid-bytes}
                (seesaw/parse-soil-moisture ["invalid" "bytes"])))))

(deftest parse-soil-temperature-test
  (testing "parses 4-byte response into soil temperature in Celsius (Q16.16 Fixed Point)"
    (let [res (seesaw/parse-soil-temperature ["00" "19" "00" "00"])]
      (is (= :ok (:status res)))
      (is (= 25.0 (:temperature res))))
    (let [res (seesaw/parse-soil-temperature ["00" "1f" "8b" "9b"])]
      (is (= :ok (:status res)))
      (is (= 31.55 (Double/parseDouble (format "%.2f" (:temperature res)))))))

  (testing "handles invalid 4-byte temperature response gracefully"
    (is (match? {:status       :error
                 :error/reason :invalid-response-length}
                (seesaw/parse-soil-temperature ["00" "19"])))
    (is (match? {:status       :error
                 :error/reason :invalid-bytes}
                (seesaw/parse-soil-temperature ["00" "19" "invalid" "00"])))))

(deftest valid-moisture?-test
  (testing "validates capacitive moisture readings within physical range 250..2000"
    (is (true? (#'seesaw/valid-moisture? 500)))
    (is (true? (#'seesaw/valid-moisture? 1000)))
    (is (false? (#'seesaw/valid-moisture? 122)) "Rejects underflow noise (< 250)")
    (is (false? (#'seesaw/valid-moisture? 33771)) "Rejects byte-offset overflow noise (0x83EB)")
    (is (false? (#'seesaw/valid-moisture? 65535)) "Rejects hardware bus-busy flag (0xFFFF)")
    (is (false? (#'seesaw/valid-moisture? nil)))))

(deftest median-test
  (testing "computes median filtering on raw sequences"
    (is (= 500 (#'seesaw/median [300 500 1000])))
    (is (= 950 (#'seesaw/median [122 950 33771])))
    (is (nil? (#'seesaw/median [])))))

(deftest despike-samples-test
  (testing "Filters out false max-saturation (>= 900) spikes when baseline readings (< 600) exist"
    (is (= [340 342 345]
           (#'seesaw/despike-samples [340 342 345 1015 1015 1016] 600.0 900.0))))

  (testing "Preserves all readings when sensor is in water or saturated soil (min-val >= 600)"
    (is (= [950 1015 1015 1016]
           (#'seesaw/despike-samples [950 1015 1015 1016] 600.0 900.0))))

  (testing "Preserves clean readings in normal soil without spikes"
    (is (= [550 560 555]
           (#'seesaw/despike-samples [550 560 555] 600.0 900.0))))

  (testing "Handles empty sequence gracefully"
    (is (= [] (#'seesaw/despike-samples [] 600.0 900.0)))
    (is (nil? (#'seesaw/despike-samples nil 600.0 900.0)))))

(deftest raw->moisture-pct-test
  (testing "Normalizes raw capacitive moisture readings within bounds"
    (is (= 0.0 (seesaw/raw->moisture-pct 340 340 1015)))
    (is (= 100.0 (seesaw/raw->moisture-pct 1015 340 1015)))
    (is (= 50.0 (seesaw/raw->moisture-pct 677.5 340 1015))))

  (testing "Clamps values below dry baseline to 0.0"
    (is (= 0.0 (seesaw/raw->moisture-pct 200 340 1015))))

  (testing "Clamps values above wet baseline to 100.0"
    (is (= 100.0 (seesaw/raw->moisture-pct 1200 340 1015)))))
