(ns statusline
  "A statusline display utility for Claude Code that tracks session and daily durations.
  
  This script reads JSON data from stdin containing model information, cost data, and
  transcript paths, then displays a formatted statusline with:
  - Colored model name based on model type
  - Cost information
  - Daily usage duration (time since first prompt today)
  - Session duration (time since current session started)
  
  State is persisted in ~/.claude/statusline.edn to track daily first prompt times."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [braille-color :as bc]))

;; ---------- state management ----------
(def state-file-path
  "Path to the persistent state file for tracking daily and hourly metrics."
  (io/file (System/getProperty "user.home") ".claude" "statusline.edn"))

(defn load-state
  "Load state from statusline.edn, creating it with empty map if it doesn't exist.
  
  Returns:
    State map from file or empty map if file doesn't exist or can't be read."
  []
  (if (.exists state-file-path)
    (try
      (edn/read-string (slurp state-file-path))
      (catch Exception e
        (println (str "Warning: Could not read state file: " (.getMessage e)))
        {}))
    (do
      (spit state-file-path "{}")
      {})))

(defn save-state
  "Save state to statusline.edn.
  
  Args:
    state - State map to persist to disk
    
  Side effects:
    Writes to state file, prints warning if write fails."
  [state]
  (try
    (spit state-file-path (pr-str state))
    (catch Exception e
      (println (str "Warning: Could not save state file: " (.getMessage e))))))

;; ---------- helpers ----------
(defn parse-num
  "Parse a value to a double, handling strings, numbers, and invalid inputs.
  
  Args:
    x - The value to parse (string, number, or other)
    
  Returns:
    A double value, defaulting to 0.0 for invalid inputs."
  [x]
  (cond
    (number? x) (double x)
    (string? x) (try 
                  (if (re-matches #"^\d*\.?\d*$" x)
                    (Double/parseDouble x) 
                    0.0)
                  (catch Exception _ 0.0))
    :else 0.0))

(defn supports-color?
  "Check if the terminal supports ANSI color codes.
  
  Returns:
    true if colors are supported (NO_COLOR environment variable not set), false otherwise."
  []
  (and (not (System/getenv "NO_COLOR"))
       true))

(def ansi
  "ANSI color codes for terminal output formatting."
  {:reset "\u001b[0m"
   :green "\u001b[32m"
   :bright-green "\u001b[92m"
   :yellow "\u001b[33m"
   :bright-yellow "\u001b[93m"
   :bright-orange "\u001b[38;5;214m"
   :bright-red "\u001b[38;2;255;0;0m"
   :red "\u001b[31m"
   :cyan "\u001b[36m"
   :magenta "\u001b[35m"
   :blue "\u001b[34m"})

(defn color-for-model
  "Get the appropriate ANSI color code for a given model name.
  
  Args:
    model-name - The name of the model (string)
    
  Returns:
    ANSI color code string based on model type:
    - Sonnet models: bright green
    - Opus models: bright red  
    - Haiku models: cyan
    - Other models: reset (no color)"
  [model-name]
  (let [lower-model (str/lower-case model-name)]
    (cond
      (str/includes? lower-model "sonnet") (:bright-green ansi)
      (str/includes? lower-model "opus") (:bright-red ansi)
      (str/includes? lower-model "haiku") (:cyan ansi)
      :else (:reset ansi))))

(defn parse-iso-timestamp
  "Parse ISO 8601 timestamp to milliseconds since epoch.
  
  Args:
    ts-str - ISO 8601 formatted timestamp string (e.g., '2025-07-01T10:43:40.323Z')
    
  Returns:
    Milliseconds since epoch, or nil if parsing fails."
  [ts-str]
  (try
    ;; Parse ISO format: 2025-07-01T10:43:40.323Z
    (when (and ts-str (string? ts-str))
      ;; Use Java interop to parse ISO timestamp
      (.toEpochMilli (java.time.Instant/parse ts-str)))
    (catch Exception _ nil)))

(defn format-duration 
  "Format milliseconds into human-readable duration"
  [ms]
  (let [seconds (quot ms 1000)
        minutes (quot seconds 60)
        hours (quot minutes 60)
        remaining-minutes (mod minutes 60)]
    (cond
      (> hours 0) (format "%d:%02d" hours remaining-minutes)
      (> minutes 0) (format "0:%02d" minutes)
      :else "<1m")))

(defn is-same-day? 
  "Check if two timestamps (millis) are on the same day in local timezone"
  [ts1 ts2]
  (try
    (let [instant1 (java.time.Instant/ofEpochMilli ts1)
          instant2 (java.time.Instant/ofEpochMilli ts2)
          zone (java.time.ZoneId/systemDefault)
          date1 (.toLocalDate (.atZone instant1 zone))
          date2 (.toLocalDate (.atZone instant2 zone))]
      (= date1 date2))
    (catch Exception _ false)))

(defn get-current-date-string
  "Get the current date as a string in YYYY-MM-DD format in local timezone.
  
  Returns:
    String representation of today's date"
  []
  (let [zone (java.time.ZoneId/systemDefault)
        local-date (java.time.LocalDate/now zone)]
    (str local-date)))

(defn get-current-hour
  "Get the current hour (0-23) in local timezone.
  
  Returns:
    Integer representing the current hour"
  []
  (let [zone (java.time.ZoneId/systemDefault)
        local-time (java.time.LocalDateTime/now zone)]
    (.getHour local-time)))

(defn should-update-hourly-chart?
  "Check if we should update the hourly chart buckets.
  This happens when we cross an hour boundary or start a new day.
  
  Args:
    state - Current state map
    current-date - Today's date string
    current-hour - Current hour (0-23)
    
  Returns:
    true if we should update and write state, false otherwise"
  [state current-date current-hour]
  (let [stored-date (get-in state [:hourly-costs :date])
        last-hour (:last-recorded-hour state)]
    (or (not= stored-date current-date)  ; New day
        (not= last-hour current-hour))))  ; New hour

(defn update-daily-cost-tracking
  "Update daily cost tracking with current cumulative cost.
  Establishes baseline on first prompt of day, tracks daily total.
  
  Args:
    state - Current state map
    current-date - Today's date string  
    cost - Current cumulative cost in USD
    
  Returns:
    Updated state map with daily baseline"
  [state current-date cost]
  (let [stored-date (get-in state [:daily-costs :date])
        is-new-day? (not= stored-date current-date)
        existing-baseline (get-in state [:daily-costs :baseline])
        
        ;; Set baseline: new day uses current cost, same day preserves existing baseline
        ;; If no existing baseline (first ever run), use current cost
        daily-baseline (cond
                        is-new-day? cost
                        existing-baseline existing-baseline  ; Keep existing baseline
                        :else cost)]  ; First ever run, establish baseline
    
    (-> state
        (assoc-in [:daily-costs :date] current-date)
        (assoc-in [:daily-costs :baseline] daily-baseline)
        (assoc :current-total-cost cost))))

(defn calculate-todays-cost-from-baseline
  "Calculate today's cost based on daily baseline.
  
  Args:
    state - Current state map
    
  Returns:
    Today's cost (current total - daily baseline)"
  [state]
  (let [baseline (get-in state [:daily-costs :baseline] 0.0)
        current-total (:current-total-cost state 0.0)]
    (max 0.0 (- current-total baseline))))

(defn update-hourly-chart
  "Update hourly chart buckets with current daily cost.
  Each hour shows the total daily cost at that time.
  
  Args:
    state - Current state map
    current-date - Today's date string
    current-hour - Current hour (0-23)
    todays-cost - Today's total cost so far
    
  Returns:
    Updated state map with hourly chart data"
  [state current-date current-hour todays-cost]
  (let [stored-date (get-in state [:hourly-costs :date])
        is-new-day? (not= stored-date current-date)
        
        ;; Reset buckets if new day, otherwise keep existing
        buckets (if is-new-day?
                  {}
                  (get-in state [:hourly-costs :buckets] {}))
        
        ;; Set current hour to today's total cost
        updated-buckets (assoc buckets current-hour todays-cost)]
    
    (-> state
        (assoc-in [:hourly-costs :date] current-date)
        (assoc-in [:hourly-costs :buckets] updated-buckets)
        (assoc :last-recorded-hour current-hour))))

(defn get-first-prompt-today 
  "Find the first user prompt from today in the transcript"
  [transcript-path current-time]
  (try
    (when (and transcript-path (.exists (io/file transcript-path)))
      (with-open [rdr (io/reader transcript-path)]
        (let [lines (line-seq rdr)]
          (->> lines
               (keep (fn [line]
                       (try
                         (let [data (json/parse-string line true)]
                           (when (and (= (:type data) "user")
                                      (:timestamp data))
                             (let [ts (parse-iso-timestamp (:timestamp data))]
                               (when (and ts (is-same-day? ts current-time))
                                 ts))))
                         (catch Exception _ nil))))
               first))))
    (catch Exception _ nil)))

(defn parse-transcript-line
  "Parse a single line from the JSONL transcript file.
  
  Args:
    line - A string line from the transcript file
    
  Returns:
    A map with parsed data and :parsed-timestamp, or nil if parsing fails."
  [line]
  (try
    (let [data (json/parse-string line true)
          entry-type (get data :type)]
      ;; Only keep user and assistant messages with timestamps
      (when (and (or (= entry-type "user")
                     (= entry-type "assistant"))
                 (get data :timestamp))
        (assoc data :parsed-timestamp 
               (parse-iso-timestamp (get data :timestamp)))))
    (catch Exception _ nil)))

(defn parse-transcript-entries
  "Parse all entries from transcript lines, filtering for valid messages.
  
  Args:
    lines - Sequence of lines from the transcript file
    
  Returns:
    Sequence of parsed entries sorted by timestamp."
  [lines]
  (->> lines
       (keep parse-transcript-line)
       (sort-by :parsed-timestamp)))

(defn find-session-boundaries
  "Find session start time by detecting gaps larger than session duration.
  
  Claude Code has a 5-hour usage window. The transcript file persists across
  multiple usage windows/sessions. To find the CURRENT session, we:
  1. Sort all messages by timestamp
  2. Iterate through messages looking for gaps > 5 hours between consecutive messages
  3. When we find a gap > 5 hours, that marks the start of a new session
  4. Keep track of the most recent session start
  5. The last session start we find is the beginning of the current session
  This matches how ccusage identifies 'session blocks' in their TypeScript code.
  
  Args:
    sorted-entries - Sequence of entries sorted by :parsed-timestamp
    
  Returns:
    Timestamp (milliseconds) of the current session start, or nil if no entries."
  [sorted-entries]
  (when-not (empty? sorted-entries)
    (let [session-duration-ms (* 5 60 60 1000)] ; 5 hours in milliseconds
      (loop [entries sorted-entries
             session-start (:parsed-timestamp (first sorted-entries))
             prev-ts (:parsed-timestamp (first sorted-entries))]
        (if-let [entry (first entries)]
          (let [ts (:parsed-timestamp entry)]
            (if (> (- ts prev-ts) session-duration-ms)
              ;; Gap found - new session starts here
              (recur (rest entries) ts ts)
              ;; Same session continues
              (recur (rest entries) session-start ts)))
          ;; Return the start of the most recent session
          session-start)))))

(defn get-session-start-from-transcript
  "Read the JSONL transcript and find CURRENT session start time.
  
  Args:
    transcript-path - Path to the transcript file
    
  Returns:
    Timestamp (milliseconds) of current session start, or nil if not found."
  [transcript-path]
  (try
    (when (and transcript-path (.exists (io/file transcript-path)))
      (with-open [rdr (io/reader transcript-path)]
        (let [lines (line-seq rdr)
              sorted-entries (parse-transcript-entries lines)]
          (find-session-boundaries sorted-entries))))
    (catch Exception _ nil)))

;; ---------- data extraction ----------
(defn extract-model-name
  "Extract model name from input data with fallback to 'unknown'.
  
  Args:
    data - Parsed JSON data from stdin
    
  Returns:
    Model name string."
  [data]
  (or (get-in data [:model :display_name])
      (get-in data [:model :id])
      "unknown"))

(defn extract-cost-value
  "Extract cost value from input data as a number.
  
  Args:
    data - Parsed JSON data from stdin
    
  Returns:
    Cost as double, defaults to 0.0."
  [data]
  (parse-num (get-in data [:cost :total_cost_usd] 0.0)))

(defn format-cost
  "Format a numeric cost value as USD string.
  
  Args:
    cost - Numeric cost value
    
  Returns:
    Formatted string like '$12.34'."
  [cost]
  (format "$%.2f" (double cost)))

(defn format-model-with-color
  "Format model name with appropriate color coding.
  
  Args:
    model - The model name string
    color? - Boolean indicating if colors should be applied
    
  Returns:
    String with model name, optionally colored."
  [model color?]
  (if color?
    (str (color-for-model model) model (:reset ansi))
    model))

(defn calculate-session-duration
  "Calculate formatted session duration from session start time.
  
  Args:
    session-start - Session start timestamp in milliseconds, or nil
    current-time - Current timestamp in milliseconds
    
  Returns:
    Formatted duration string, or nil if session-start is nil or invalid."
  [session-start current-time]
  (when session-start
    (let [elapsed (- current-time session-start)]
      (when (pos? elapsed)
        (format-duration elapsed)))))

(defn update-daily-first-prompt
  "Update and persist the first prompt time for today.
  
  Args:
    state - Current state map
    transcript-path - Path to transcript file
    current-time - Current timestamp in milliseconds
    
  Returns:
    Map with :first-prompt-time and updated :state keys."
  [state transcript-path current-time]
  (let [stored-time (:today-first-prompt-time state)
        ;; Check if stored time is from today
        today-first-prompt-time (when (and stored-time
                                           (is-same-day? stored-time current-time))
                                  stored-time)
        ;; If we don't have today's first prompt time, try to find it
        today-first-prompt-time (or today-first-prompt-time
                                    (get-first-prompt-today transcript-path current-time))
        ;; Determine if state needs updating
        needs-prompt-update? (and today-first-prompt-time
                                 (not= today-first-prompt-time stored-time))
        
        ;; Update state with new first prompt time if needed
        updated-state (if needs-prompt-update?
                       (assoc state :today-first-prompt-time today-first-prompt-time)
                       state)]
    
    {:first-prompt-time today-first-prompt-time
     :state updated-state
     :needs-save? needs-prompt-update?}))

(defn calculate-daily-duration
  "Calculate formatted daily duration from first prompt time.
  
  Args:
    first-prompt-time - First prompt timestamp in milliseconds, or nil
    current-time - Current timestamp in milliseconds
    
  Returns:
    Formatted duration string, or nil if first-prompt-time is nil or invalid."
  [first-prompt-time current-time]
  (when first-prompt-time
    (let [elapsed (- current-time first-prompt-time)]
      (when (pos? elapsed)
        (format-duration elapsed)))))

;; ---------- braille sparkline (12-level) ----------
(def ^:private ansi-reset "\u001b[0m")

(defn- ansi-truecolor 
  "Generate ANSI truecolor escape sequence."
  [r g b]
  (format "\u001b[38;2;%d;%d;%dm" r g b))

(def ^:private ticks ["⡀" "⡄" "⡆" "⡇"]) ; 4 clean, bottom-aligned single-column braille bars
(def ^:private greys24 [[180 180 180] [220 220 220] [255 255 255]]) ; light → white for dark terminals

(defn generate-sparkline
  "Render a one-line sparkline using 4 braille heights × 3 brightness levels = 12 steps.
  
  Args:
    data - Sequence of numbers to visualize
    
  Returns:
    String sparkline with ANSI color sequences using truecolor mode"
  [data]
  (let [xs (vec data)
        n (count xs)]
    (cond
      (zero? n) ""
      (apply = xs)
      (let [gi 1               ; middle-ish brightness
            ti 1               ; middle-ish height
            ch (nth ticks ti)
            [r g b] (nth greys24 gi)
            wrap (ansi-truecolor r g b)]
        (apply str (repeat n (str wrap ch ansi-reset))))

      :else
      (let [mn (reduce min xs)
            mx (reduce max xs)
            rng (max 1e-12 (- mx mn))
            ;; 12 levels: 0..11
            level (fn [x]
                    (let [x' (min mx (max mn x)) ; clamp
                          t (/ (- x' mn) rng)
                          ;; round to nearest 0..11
                          L (int (Math/round (* 11.0 (double t))))]
                      (min 11 (max 0 L))))
            ;; height index 0..3, brightness 0..2
            H (fn [L] (quot L 3))
            B (fn [L] (mod L 3))
            emit (fn [bi ch]
                   (let [[r g b] (nth greys24 bi)]
                     (str (ansi-truecolor r g b) ch ansi-reset)))]
        (apply str
               (for [x xs
                     :let [L (level x)
                           ti (H L)
                           bi (B L)
                           ch (nth ticks ti)]]
                 (emit bi ch)))))))

(defn extract-hourly-costs-for-sparkline
  "Extract hourly cost values for the current day to display as sparkline.
  Skips leading zero values to make the sparkline more compact.
  
  Args:
    state - Current state map containing hourly costs
    current-hour - Current hour (0-23)
    
  Returns:
    Vector of cost values from first non-zero hour to current hour"
  [state current-hour]
  (let [buckets (get-in state [:hourly-costs :buckets] {})
        ;; Get all hours from 0 to current hour
        all-hours (range (inc current-hour))
        ;; Find first hour with non-zero cost
        first-non-zero-hour (first (filter #(pos? (get buckets % 0.0)) all-hours))]
    (if first-non-zero-hour
      ;; Return from first non-zero hour to current hour
      (vec (for [h (range first-non-zero-hour (inc current-hour))]
             (get buckets h 0.0)))
      ;; If all zeros, return empty vector
      [])))


(defn format-hourly-sparkline
  "Format the hourly costs as a sparkline with built-in grayscale coloring.
  
  Args:
    state - Current state map
    current-hour - Current hour (0-23)
    color-enabled? - Whether to use color (ignored, sparkline has built-in colors)
    
  Returns:
    Formatted sparkline string or nil if no data"
  [state current-hour _color-enabled?]
  (let [hourly-data (extract-hourly-costs-for-sparkline state current-hour)]
    (when (and (seq hourly-data) 
               (some pos? hourly-data))  ; Only show if there's actual cost data
      (generate-sparkline hourly-data))))


;; ---------- main ----------
(defn -main
  "Main entry point for the statusline script.
  
  Reads JSON data from stdin and outputs a formatted statusline with model info,
  cost, daily duration, and session duration."
  []
  (try
    (let [;; Load state and parse input data
          state (load-state)
          data (json/parse-string (slurp *in* :encoding "UTF-8") true)
          current-time (System/currentTimeMillis)
          
          ;; Extract basic information
          model-name (extract-model-name data)
          cost-value (extract-cost-value data)  ; Total cumulative cost (for delta calculations)
          colored-model (format-model-with-color model-name (supports-color?))
          exceeds-limit? (get data :exceeds_200k_tokens false)
          
          ;; Calculate session duration
          transcript-path (get data :transcript_path)
          session-start (get-session-start-from-transcript transcript-path)
          session-duration (calculate-session-duration session-start current-time)
          
          ;; Handle daily tracking
          {:keys [first-prompt-time state needs-save?]} 
          (update-daily-first-prompt state transcript-path current-time)
          daily-duration (calculate-daily-duration first-prompt-time current-time)
          
          ;; Handle daily cost tracking and hourly chart
          current-date (get-current-date-string)
          current-hour (get-current-hour)
          
          ;; Update daily baseline tracking
          state-with-baseline (update-daily-cost-tracking state current-date cost-value)
          todays-cost (calculate-todays-cost-from-baseline state-with-baseline)
          
          ;; Check if we need to update hourly chart (hour boundary crossed)
          should-update-chart? (should-update-hourly-chart? state-with-baseline current-date current-hour)
          
          ;; Update hourly chart if needed
          final-state (if should-update-chart?
                        (update-hourly-chart state-with-baseline current-date current-hour todays-cost)
                        state-with-baseline)
          
          ;; Save state if either daily prompt or hourly chart were updated
          _ (when (or needs-save? should-update-chart?)
              (save-state final-state))
          
          ;; Generate colored braille hourly chart visualization with 5-hour blocks
          first-prompt-hour (when first-prompt-time
                              (let [zone (java.time.ZoneId/systemDefault)
                                    instant (java.time.Instant/ofEpochMilli first-prompt-time)
                                    local-time (.atZone instant zone)]
                                (.getHour local-time)))
          hourly-costs (get-in final-state [:hourly-costs :buckets] {})
          hourly-chart (if (and first-prompt-hour (seq hourly-costs))
                        (bc/multi-block-chart hourly-costs first-prompt-hour)
                        ;; For testing: generate sample data if no real data exists
                        (when first-prompt-hour
                          (let [sample-costs (bc/generate-sample-hourly-costs first-prompt-hour 12)]
                            (bc/multi-block-chart sample-costs first-prompt-hour))))
          cost-str (format-cost todays-cost)
          
          ;; Build output with new order: model, daily-duration, session-duration, hourly-chart, cost
          parts (cond-> [colored-model]
                  daily-duration (conj daily-duration)
                  session-duration (conj session-duration)
                  hourly-chart (conj hourly-chart)
                  true (conj cost-str)  ; Cost always comes after chart
                  (and (not daily-duration) (not session-duration) (not hourly-chart) exceeds-limit?)
                  (conj "[>200k tokens]"))]

      ;; Output final statusline on a single line
      (println (str/join " " parts)))
    (catch Exception e
      ;; Provide a meaningful fallback with error context
      (let [error-msg (or (.getMessage e) "Unknown error")]
        (println (str "Claude Code [Error: " error-msg "]"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))