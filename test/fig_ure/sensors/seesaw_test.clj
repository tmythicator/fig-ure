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
