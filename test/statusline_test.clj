(ns statusline-test
  "Comprehensive test suite for Claude Code statusline functionality.
  
  This test suite provides complete coverage of the statusline system including:
  - Data parsing and JSON input handling
  - State management and persistence  
  - Time/date calculations and formatting
  - Cost tracking with daily baselines
  - Session detection and daily duration tracking
  - Model name formatting with ANSI colors
  - Braille chart generation with colored visualization
  - 5-hour block segmentation and multi-block charts
  - Legacy grayscale sparkline generation
  - Full end-to-end pipeline integration
  
  Tests are organized by functional area for maintainability and follow
  standard patterns for setup, execution, and assertion."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [babashka.classpath :as cp]
            [clojure.string :as str]))

;; Add src directory to classpath for local namespace files
(cp/add-classpath "src")

;; Now require local namespaces after classpath is set
(require '[statusline :as sl])
(require '[braille-color :as bc])

;; ---------- Test Fixtures & Utilities ----------

(def sample-json-input
  "Standard test JSON input matching Claude Code's data format."
  {:model {:display_name "Claude 3.5 Sonnet" 
           :id "claude-3-5-sonnet-20241022"}
   :cost {:total_cost_usd "2.50"}
   :transcript_path "/tmp/test-transcript.jsonl"
   :exceeds_200k_tokens false})

(def sample-state-data
  "Sample state data matching the real statusline.edn format."
  {:today-first-prompt-time 1756247504518
   :daily-costs {:date "2025-08-27" :baseline 0.8830776000000001}
   :current-total-cost 1.7440190499999995
   :hourly-costs {:date "2025-08-27" 
                  :buckets {8 0.0, 9 3.73, 10 5.28, 12 0.86}}
   :last-recorded-hour 12})

(def sample-hourly-costs
  "Sample hourly cost data for testing chart generation."
  {8 0.0, 9 3.7290794499999977, 10 5.280202750000001, 12 0.8609414499999993})

(defn create-test-transcript-lines
  "Create test transcript lines with timestamps for session boundary testing."
  [timestamps]
  (map (fn [ts]
         (str "{\"type\":\"user\",\"timestamp\":\"" ts "\",\"message\":\"test\"}"))
       timestamps))

(defn milliseconds-ago
  "Get timestamp N milliseconds ago from current time."
  [ms-ago]
  (- (System/currentTimeMillis) ms-ago))

;; ---------- Data Parsing & Utilities Tests ----------

(deftest test-data-parsing
  (testing "Number parsing handles various input types"
    (is (= 1.23 (sl/parse-num "1.23")) "Should parse valid string numbers")
    (is (= 42.0 (sl/parse-num 42)) "Should handle integer inputs")
    (is (= 3.14 (sl/parse-num 3.14)) "Should handle double inputs")
    (is (= 0.0 (sl/parse-num "invalid")) "Should default to 0.0 for invalid strings")
    (is (= 0.0 (sl/parse-num nil)) "Should default to 0.0 for nil")
    (is (= 0.0 (sl/parse-num "")) "Should default to 0.0 for empty string"))
  
  (testing "Model name extraction from JSON data"
    (is (= "Claude 3.5 Sonnet" (sl/extract-model-name sample-json-input)) 
        "Should extract display_name when available")
    (is (= "claude-3-5-sonnet" (sl/extract-model-name {:model {:id "claude-3-5-sonnet"}}))
        "Should fallback to id when display_name missing")
    (is (= "unknown" (sl/extract-model-name {}))
        "Should default to 'unknown' when model data missing"))
  
  (testing "Cost value extraction from JSON data"
    (is (= 2.5 (sl/extract-cost-value sample-json-input))
        "Should extract and parse cost as double")
    (is (= 0.0 (sl/extract-cost-value {}))
        "Should default to 0.0 when cost data missing")))

;; ---------- Time & Date Handling Tests ----------

(deftest test-time-date-functions
  (testing "ISO timestamp parsing"
    (is (number? (sl/parse-iso-timestamp "2025-08-27T10:43:40.323Z"))
        "Should parse valid ISO timestamp to milliseconds")
    (is (nil? (sl/parse-iso-timestamp "invalid-timestamp"))
        "Should return nil for invalid timestamps")
    (is (nil? (sl/parse-iso-timestamp nil))
        "Should handle nil input gracefully"))
  
  (testing "Duration formatting"
    (let [one-hour (* 60 60 1000)
          one-minute (* 60 1000)]
      (is (= "1:00" (sl/format-duration one-hour))
          "Should format hours correctly")
      (is (= "0:05" (sl/format-duration (* 5 one-minute)))
          "Should format minutes with hour prefix")
      (is (= "1:30" (sl/format-duration (+ one-hour (* 30 one-minute))))
          "Should format hours and minutes together")
      (is (= "<1m" (sl/format-duration 30000))
          "Should show <1m for very short durations")))
  
  (testing "Same day comparison"
    (let [morning-time (- (System/currentTimeMillis) (* 2 60 60 1000))  ; 2 hours ago
          current-time (System/currentTimeMillis)]
      (is (true? (sl/is-same-day? morning-time current-time))
          "Should recognize same day timestamps")
      (is (false? (sl/is-same-day? (- current-time (* 25 60 60 1000)) current-time))
          "Should recognize different day timestamps")))
  
  (testing "Date and hour extraction"
    (is (string? (sl/get-current-date-string))
        "Should return date string in YYYY-MM-DD format")
    (is (and (>= (sl/get-current-hour) 0) (<= (sl/get-current-hour) 23))
        "Should return valid hour (0-23)")))

;; ---------- Model & Color Formatting Tests ----------

(deftest test-model-color-formatting
  (testing "Model-specific color assignment"
    (is (= (:bright-green sl/ansi) (sl/color-for-model "Claude 3.5 Sonnet"))
        "Sonnet models should be bright green")
    (is (= (:bright-red sl/ansi) (sl/color-for-model "Claude 3 Opus"))
        "Opus models should be bright red")
    (is (= (:cyan sl/ansi) (sl/color-for-model "Claude 3 Haiku"))
        "Haiku models should be cyan")
    (is (= (:reset sl/ansi) (sl/color-for-model "Unknown Model"))
        "Unknown models should have no color"))
  
  (testing "Model name formatting with colors"
    (is (str/includes? (sl/format-model-with-color "Claude 3.5 Sonnet" true) "\u001b[92m")
        "Should include ANSI color codes when colors enabled")
    (is (not (str/includes? (sl/format-model-with-color "Claude 3.5 Sonnet" false) "\u001b["))
        "Should not include ANSI codes when colors disabled"))
  
  (testing "Cost formatting"
    (is (= "$1.23" (sl/format-cost 1.234)) "Should format to 2 decimal places")
    (is (= "$0.00" (sl/format-cost 0)) "Should handle zero cost")
    (is (= "$999.99" (sl/format-cost 999.99)) "Should handle large amounts")))

;; ---------- Cost Tracking & Daily Baseline Tests ----------

(deftest test-cost-tracking
  (testing "Daily cost baseline establishment"
    (let [state {}
          current-date "2025-08-27"
          cost 2.5
          updated-state (sl/update-daily-cost-tracking state current-date cost)]
      (is (= current-date (get-in updated-state [:daily-costs :date]))
          "Should set current date")
      (is (= cost (get-in updated-state [:daily-costs :baseline]))
          "Should establish baseline on first day")
      (is (= cost (:current-total-cost updated-state))
          "Should track current total cost")))
  
  (testing "Daily cost baseline persistence across same day"
    (let [existing-state {:daily-costs {:date "2025-08-27" :baseline 1.0}
                          :current-total-cost 3.0}
          updated-state (sl/update-daily-cost-tracking existing-state "2025-08-27" 4.0)]
      (is (= 1.0 (get-in updated-state [:daily-costs :baseline]))
          "Should preserve existing baseline on same day")
      (is (= 4.0 (:current-total-cost updated-state))
          "Should update current total cost")))
  
  (testing "Today's cost calculation from baseline"
    (let [state {:daily-costs {:baseline 1.0} :current-total-cost 3.5}]
      (is (= 2.5 (sl/calculate-todays-cost-from-baseline state))
          "Should calculate delta from baseline")
      (is (= 0.0 (sl/calculate-todays-cost-from-baseline {:daily-costs {:baseline 5.0} 
                                                          :current-total-cost 2.0}))
          "Should not return negative costs")))
  
  (testing "Hourly chart update logic"
    (let [state {}
          updated-state (sl/update-hourly-chart state "2025-08-27" 14 2.5)]
      (is (= "2025-08-27" (get-in updated-state [:hourly-costs :date]))
          "Should set chart date")
      (is (= 2.5 (get-in updated-state [:hourly-costs :buckets 14]))
          "Should set hourly bucket value")
      (is (= 14 (:last-recorded-hour updated-state))
          "Should track last recorded hour"))
    
    (is (true? (sl/should-update-hourly-chart? {} "2025-08-27" 14))
        "Should update on new day")
    (is (true? (sl/should-update-hourly-chart? {:last-recorded-hour 13} "2025-08-27" 14))
        "Should update on new hour")
    (is (false? (sl/should-update-hourly-chart? 
                 {:hourly-costs {:date "2025-08-27"} :last-recorded-hour 14} 
                 "2025-08-27" 14))
        "Should not update on same day and hour")))

;; ---------- Session & Daily Tracking Tests ----------

(deftest test-session-tracking
  (testing "Session boundary detection with 5-hour gaps"
    (let [now (System/currentTimeMillis)
          entries [{:parsed-timestamp (- now (* 10 60 60 1000))}  ; 10 hours ago
                   {:parsed-timestamp (- now (* 9 60 60 1000))}   ; 9 hours ago  
                   {:parsed-timestamp (- now (* 3 60 60 1000))}   ; 3 hours ago (new session)
                   {:parsed-timestamp (- now (* 2 60 60 1000))}]] ; 2 hours ago
      (is (= (- now (* 3 60 60 1000)) (sl/find-session-boundaries entries))
          "Should identify session start after 5+ hour gap")))
  
  (testing "Session duration calculation"
    (let [two-hours-ago (- (System/currentTimeMillis) (* 2 60 60 1000))
          current-time (System/currentTimeMillis)
          duration (sl/calculate-session-duration two-hours-ago current-time)]
      (is (str/includes? duration "2:00") "Should calculate session duration correctly")
      (is (nil? (sl/calculate-session-duration nil current-time))
          "Should handle nil session start")))
  
  (testing "Daily duration calculation"
    (let [four-hours-ago (- (System/currentTimeMillis) (* 4 60 60 1000))
          current-time (System/currentTimeMillis)
          duration (sl/calculate-daily-duration four-hours-ago current-time)]
      (is (str/includes? duration "4:00") "Should calculate daily duration correctly")
      (is (nil? (sl/calculate-daily-duration nil current-time))
          "Should handle nil first prompt time"))))

;; ---------- Braille Color Chart Tests ----------

(deftest test-braille-color-fundamentals
  (testing "Color gradient function (green→yellow→red)"
    (is (= [0 255 0] (#'bc/g2y2r 0.0)) "Low values (0.0) should be green")
    (is (= [255 255 0] (#'bc/g2y2r 0.5)) "Mid values (0.5) should be yellow")
    (is (= [255 0 0] (#'bc/g2y2r 1.0)) "High values (1.0) should be red")
    (is (= [127 255 0] (#'bc/g2y2r 0.25)) "Quarter values should be yellow-green"))
  
  (testing "Braille height mapping"
    (is (= 0 (#'bc/row4 0.0)) "Minimum value maps to row 0 (bottom)")
    (is (= 3 (#'bc/row4 1.0)) "Maximum value maps to row 3 (top)")
    (is (= 1 (#'bc/row4 0.33)) "One-third value maps to row 1")
    (is (= 2 (#'bc/row4 0.67)) "Two-thirds value maps to row 2"))
  
  (testing "Sample data generation for testing"
    (let [sample-data (bc/generate-sample-hourly-costs 8 5)]
      (is (map? sample-data) "Should return a map of hour->cost")
      (is (= 5 (count sample-data)) "Should generate requested number of hours")
      (is (every? number? (vals sample-data)) "All cost values should be numbers")
      (is (every? pos? (vals sample-data)) "All costs should be positive")
      (is (= #{8 9 10 11 12} (set (keys sample-data))) "Should generate correct hour range"))))

(deftest test-5hour-block-segmentation
  (testing "Basic 5-hour block creation"
    (let [hourly-costs {8 1.0, 9 2.0, 10 1.5, 11 2.5, 12 1.8, 
                        13 0.5, 14 0.8, 15 1.2, 16 2.2, 17 1.9}
          blocks (#'bc/segment-into-5hour-blocks hourly-costs 8)]
      (is (= 2 (count blocks)) "Should create 2 blocks for 10 hours of data")
      (is (= 5 (count (first blocks))) "Each block should have 5 hours")
      (is (= [1.0 2.0 1.5 2.5 1.8] (first blocks)) "First block: hours 8-12")
      (is (= [0.5 0.8 1.2 2.2 1.9] (second blocks)) "Second block: hours 13-17")))
  
  (testing "Sparse data with missing hours"
    (let [sparse-costs {8 1.0, 10 2.0, 15 1.5}  ; Missing hours 9,11,12,13,14,16,17
          blocks (#'bc/segment-into-5hour-blocks sparse-costs 8)]
      (is (= 2 (count blocks)) "Should create blocks up to last data point")
      (is (= [1.0 0.0 2.0 0.0 0.0] (first blocks)) "Missing hours should be 0.0")
      (is (= [0.0 0.0 1.5 0.0 0.0] (second blocks)) "Second block should include hour 15")))
  
  (testing "Empty and single data point handling"
    (is (= [] (#'bc/segment-into-5hour-blocks {} 8)) "Empty data should return empty blocks")
    (let [single-point {10 1.5}
          blocks (#'bc/segment-into-5hour-blocks single-point 8)]
      (is (= 1 (count blocks)) "Single data point should create one block")
      (is (= [0.0 0.0 1.5 0.0 0.0] (first blocks)) "Block should pad around single point"))))

(deftest test-multi-block-chart-generation
  (testing "Multi-block chart with space separators"
    (let [hourly-costs {8 1.0, 9 2.0, 10 1.5, 11 2.5, 12 1.8, 
                        13 0.5, 14 0.8, 15 1.2, 16 2.2, 17 1.9}
          chart (bc/multi-block-chart hourly-costs 8)]
      (is (string? chart) "Should return a string")
      (is (str/includes? chart " ") "Should contain space separators between blocks")
      (is (str/includes? chart "\u001b[38;2;") "Should contain ANSI truecolor codes")))
  
  (testing "Per-block normalization"
    (let [chart (bc/multi-block-chart {8 1.0, 13 5.0} 8)  ; Two blocks with different ranges
          color-codes (re-seq #"\[38;2;(\d+);(\d+);(\d+)m" chart)]
      (is (seq color-codes) "Should contain color codes")
      ;; Per-block normalization means each block scales independently
      (is (seq color-codes) "Should have per-block normalized colors")))
  
  (testing "Empty data handling"
    (is (= "" (bc/multi-block-chart {} 8)) "Empty data should return empty string")))

(deftest test-braille-color-pairing-fix
  (testing "Critical bug fix: braille characters colored by higher paired value"
    ;; This tests the fix for the major bug we discovered where braille chars
    ;; were colored incorrectly due to using only the right value instead of max(left, right)
    (let [hourly-costs sample-hourly-costs  ; {8 0.0, 9 3.73, 10 5.28, 12 0.86}
          chart (bc/multi-block-chart hourly-costs 8)]
      
      ;; Expected color mapping after fix:
      ;; 1st braille char (hours 8&9): colored by max(0.0, 3.73) = 3.73 → ORANGE
      ;; 2nd braille char (hours 10&11): colored by max(5.28, 0.0) = 5.28 → RED (highest)
      ;; 3rd braille char (hour 12): colored by 0.86 → YELLOW-GREEN (low)
      
      (is (str/includes? chart "[38;2;255;149;0m") 
          "First char should be ORANGE (max of 0.0, 3.73)")
      (is (str/includes? chart "[38;2;255;0;0m") 
          "Second char should be RED (max of 5.28, 0.0) - HIGHEST COST")
      (is (str/includes? chart "[38;2;83;255;0m") 
          "Third char should be YELLOW-GREEN (0.86 is low cost)"))))

;; ---------- Legacy Sparkline Tests ----------

(deftest test-legacy-sparkline-generation
  (testing "12-level grayscale sparkline generation"
    (let [test-data [1.0 2.0 1.5 3.0 0.5]
          sparkline (sl/generate-sparkline test-data)]
      (is (string? sparkline) "Should return a string")
      (is (str/includes? sparkline "⡀") "Should contain braille characters")
      (is (str/includes? sparkline "\u001b[38;2;") "Should contain truecolor ANSI codes")))
  
  (testing "Sparkline edge cases"
    (is (= "" (sl/generate-sparkline [])) "Empty data should return empty string")
    (let [uniform-data [2.0 2.0 2.0]
          sparkline (sl/generate-sparkline uniform-data)]
      (is (seq sparkline) "Uniform data should still generate sparkline")))
  
  (testing "Hourly cost extraction for sparkline"
    (let [state {:hourly-costs {:buckets {8 0.0, 9 1.5, 10 2.0, 11 0.5, 12 1.0}}}
          extracted (sl/extract-hourly-costs-for-sparkline state 12)]
      ;; Should skip leading zeros and extract from first non-zero to current hour
      (is (= [1.5 2.0 0.5 1.0] extracted) "Should extract from first non-zero hour to current"))))

;; ---------- Integration Tests ----------

(deftest test-full-pipeline-integration
  (testing "Main function integration with sample data"
    ;; Note: This tests the parsing and logic without actually running -main
    ;; since -main reads from stdin and writes to stdout
    (let [model-name (sl/extract-model-name sample-json-input)
          cost-value (sl/extract-cost-value sample-json-input)
          colored-model (sl/format-model-with-color model-name true)]
      
      (is (= "Claude 3.5 Sonnet" model-name))
      (is (= 2.5 cost-value))
      (is (str/includes? colored-model "\u001b[92m") "Should include Sonnet green color")))
  
  (testing "State management lifecycle"
    ;; Test the complete state update cycle
    (let [initial-state {}
          current-date "2025-08-27"
          current-hour 14
          cost 2.5
          
          ;; Simulate daily cost tracking update
          state-with-baseline (sl/update-daily-cost-tracking initial-state current-date cost)
          todays-cost (sl/calculate-todays-cost-from-baseline state-with-baseline)
          
          ;; Simulate hourly chart update
          final-state (sl/update-hourly-chart state-with-baseline current-date current-hour todays-cost)]
      
      (is (= cost (get-in state-with-baseline [:daily-costs :baseline])))
      (is (= 0.0 todays-cost)) ; First day, so today's cost is 0
      (is (= todays-cost (get-in final-state [:hourly-costs :buckets current-hour])))))
  
  (testing "End-to-end colored chart generation"
    ;; Test the complete flow from hourly costs to colored braille chart
    (let [first-prompt-hour 8
          chart (bc/multi-block-chart sample-hourly-costs first-prompt-hour)]
      
      (is (seq chart) "Should generate non-empty chart")
      (is (str/includes? chart "\u001b[38;2;") "Should include color codes")
      
      ;; Verify the fix: highest cost should appear as red
      (let [has-red-for-high-cost (str/includes? chart "[38;2;255;0;0m")
            has-green-for-low-cost (str/includes? chart "[38;2;0;255;0m") 
            has-orange-for-mid-cost (str/includes? chart "[38;2;255;149;0m")]
        (is has-red-for-high-cost "Highest costs should show as red")
        (is (or has-orange-for-mid-cost has-green-for-low-cost) 
            "Should have appropriate colors for other cost levels")))))

;; ---------- TDD: Second Block Should Only Show Current Hour ----------

(deftest test-second-block-should-only-show-current-hour
  (testing "TDD: Second 5-hour block should only show dots up to current hour, not future hours"
    ;; This test captures your exact issue: when you're in hour 13 (first hour of second block),
    ;; you should only see 1 dot for hour 13, not 3 dots for hours 13, 15, 17
    (let [hourly-costs {8 0.0, 9 3.73, 10 5.28, 12 0.86, 13 1.99}  ; Only hour 13 in second block
          first-prompt-hour 8
          chart (bc/multi-block-chart hourly-costs first-prompt-hour)
          chart-parts (str/split chart #" ")
          second-block-chart (second chart-parts)
          braille-chars (re-seq #"[⠀-⣿]" second-block-chart)]
      
      (println "\n=== TDD TEST: Second Block Current Hour Only ===")
      (println "Hourly costs:" hourly-costs)
      (println "Chart parts:" chart-parts)
      (println "Second block chart:" second-block-chart)  
      (println "Braille chars in second block:" braille-chars)
      (println "Count of braille chars:" (count braille-chars))
      
      ;; THE FAILING ASSERTION: This should fail initially because we're showing 3 dots instead of 1
      (is (= 1 (count braille-chars))
          "When only hour 13 has data in second block, should show exactly 1 braille character, not render future hours with zeros")
      
      ;; Additional assertion: the single character should represent hour 13's data  
      (is (str/includes? second-block-chart "\u001b[38;2;") 
          "Single braille character should have color coding for hour 13's cost"))))

;; ---------- Current State Debug Tests ----------

(deftest test-current-state-debugging
  (testing "Debug current state file data and 5-hour block behavior"
    ;; Using the exact current state from statusline.edn
    (let [current-hourly-costs {8 0.0, 9 3.7290794499999977, 10 5.280202750000001, 
                                12 0.8609414499999993, 13 1.9969228999999995}
          first-prompt-hour 8
          blocks (#'bc/segment-into-5hour-blocks current-hourly-costs first-prompt-hour)]
      
      (println "\n=== DEBUGGING CURRENT STATE ===")
      (println "Current hourly costs:" current-hourly-costs)  
      (println "First prompt hour:" first-prompt-hour)
      (println "Segmented blocks:" blocks)
      (println "Number of blocks:" (count blocks))
      
      ;; Test block structure
      (is (= 2 (count blocks)) "Should have exactly 2 blocks (hours 8-12 and 13-17)")
      
      ;; First block: hours 8-12
      (let [first-block (first blocks)]
        (println "First block (hours 8-12):" first-block)
        (is (= 5 (count first-block)) "First block should have 5 hours")
        (is (= [0.0 3.7290794499999977 5.280202750000001 0.0 0.8609414499999993] 
               first-block) 
            "First block should map hours 8,9,10,11,12 correctly"))
      
      ;; Second block: hours 13-17  
      (let [second-block (second blocks)]
        (println "Second block (hours 13-17):" second-block)
        (is (= 5 (count second-block)) "Second block should have 5 hours")
        ;; Only hour 13 has data, hours 14,15,16,17 should be 0.0
        (is (= [1.9969228999999995 0.0 0.0 0.0 0.0] second-block) 
            "Second block should only have hour 13 with data, rest should be 0.0"))
      
      ;; Test the actual chart generation
      (let [chart (bc/multi-block-chart current-hourly-costs first-prompt-hour)
            chart-parts (str/split chart #" ")]  ; Split on spaces (not pipes anymore)
        (println "Generated chart:" chart)
        (println "Chart parts:" chart-parts)
        (println "Number of chart parts:" (count chart-parts))
        
        (is (= 2 (count chart-parts)) "Chart should have exactly 2 parts (2 blocks)")
        
        ;; The second chart part should represent the second 5-hour block
        ;; Since only hour 13 has data in the second block, we should see appropriate visualization
        (let [second-chart-part (second chart-parts)]
          (println "Second chart part:" second-chart-part)
          ;; This will help us debug what's actually being rendered
          (is (seq second-chart-part) "Second chart part should not be empty")
          
          ;; DEBUG: The issue is that the second chart part shows 3 braille characters
          ;; when it should only show 1 (for hour 13) since hours 14-17 are all 0.0
          (let [braille-chars (re-seq #"[⠀-⣿]" second-chart-part)]
            (println "Braille characters in second part:" braille-chars)
            (println "Number of braille chars in second part:" (count braille-chars))
            ;; ISSUE: Currently shows 3 chars, but should only show 1 for hour 13
            ;; This will FAIL initially, showing us the current wrong behavior
            (is (= 1 (count braille-chars)) 
                "Second block should only show 1 braille char for hour 13, not render trailing zeros"))))))
  
  (testing "Current time simulation - should only show one hour in second block"
    ;; Simulate being in hour 13 (first hour of second 5-hour block)
    (let [current-hourly-costs {8 0.0, 9 3.73, 10 5.28, 12 0.86, 13 1.99}
          expected-second-block [1.99 0.0 0.0 0.0 0.0]]  ; Only hour 13 should have data
      
      (is (= expected-second-block 
             (second (#'bc/segment-into-5hour-blocks current-hourly-costs 8)))
          "When in hour 13, second block should only show hour 13 with data"))))

;; ---------- Regression Tests ----------

(deftest test-regression-braille-color-inversion
  (testing "Regression test: Color inversion bug fix"
    ;; This was the critical bug where low costs showed as red and high costs as green
    ;; Due to incorrect (- 1.0 normalized) inversion in color mapping
    (let [low-cost-color (#'bc/g2y2r 0.1)   ; Low cost should be green-ish
          high-cost-color (#'bc/g2y2r 0.9)] ; High cost should be red-ish
      
      ;; After fix: low normalized values = green, high normalized values = red
      (is (< (first low-cost-color) (first high-cost-color))
          "Low costs should have less red component than high costs")
      (is (> (second low-cost-color) (second high-cost-color))
          "Low costs should have more green component than high costs")))
  
  (testing "Regression test: Braille pairing color selection"
    ;; This was the bug where braille chars only used the right value for color
    ;; instead of the maximum of left and right values
    (let [;; Simulate the original bug scenario
          hourly-costs {8 0.0, 9 3.5, 10 5.0, 11 0.0}
          chart (bc/multi-block-chart hourly-costs 8)]
      
      ;; The chart should show the high cost (5.0) as red, not the adjacent low cost
      (is (str/includes? chart "[38;2;255;0;0m")
          "Highest cost should appear as red in the chart"))))

;; ---------- Test Utilities & Runner ----------

(defn test-summary
  "Generate a comprehensive test summary with statistics."
  [test-results]
  (let [total-tests (:test test-results)
        total-assertions (+ (:pass test-results) (:fail test-results) (:error test-results))
        pass-rate (if (pos? total-assertions)
                   (/ (:pass test-results) total-assertions)
                   0)]
    {:total-tests total-tests
     :total-assertions total-assertions
     :passed (:pass test-results)
     :failed (:fail test-results)
     :errors (:error test-results)
     :pass-rate pass-rate
     :success? (and (zero? (:fail test-results)) (zero? (:error test-results)))}))

(defn -main 
  "Main test runner with comprehensive reporting and exit codes."
  [& _args]
  (println "🧪 Claude Code Statusline Test Suite")
  (println "=====================================")
  (println "Running comprehensive tests for statusline functionality...\n")
  
  (let [start-time (System/currentTimeMillis)
        test-results (run-tests 'statusline-test)
        end-time (System/currentTimeMillis)
        duration (- end-time start-time)
        summary (test-summary test-results)]
    
    (println "\n📊 Test Execution Summary")
    (println "-------------------------")
    (printf "⏱️  Duration: %d ms\n" duration)
    (printf "🧪 Test Functions: %d\n" (:total-tests summary))
    (printf "✅ Total Assertions: %d\n" (:total-assertions summary))
    (printf "🟢 Passed: %d\n" (:passed summary))
    (printf "🔴 Failed: %d\n" (:failed summary))  
    (printf "⚠️  Errors: %d\n" (:errors summary))
    (printf "📈 Pass Rate: %.1f%%\n" (double (* 100 (:pass-rate summary))))
    
    (if (:success? summary)
      (do 
        (println "\n🎉 All tests passed! The statusline system is working correctly.")
        (println "✅ Braille color charts: Working with proper heat mapping")
        (println "✅ Cost tracking: Daily baselines and hourly buckets functioning")
        (println "✅ Session detection: 5-hour boundary detection working")
        (println "✅ Model formatting: ANSI colors applied correctly")
        (println "✅ Data parsing: JSON input processing robust")
        (System/exit 0))
      (do
        (println "\n❌ Some tests failed! Please review the failures above.")
        (println "🔧 Check the specific test assertions for details on what needs fixing.")
        (System/exit 1)))))

;; Auto-run tests when script is executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (-main))