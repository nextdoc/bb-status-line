(ns statusline
  "A statusline display utility for Claude Code.

  Reads JSON data from stdin and prints the model name plus 8-cell horizontal
  bars for context-window usage and the 5-hour rate-limit window."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn extract-model-name
  "Extract model name from input data, stripping parenthetical suffixes
  like '(1M context)'. Falls back to 'unknown' if absent."
  [data]
  (let [raw (or (get-in data [:model :display_name])
                (get-in data [:model :id])
                "unknown")]
    (str/trim (str/replace raw #"\s*\([^)]*\)\s*" " "))))

(def ^:private partial-chars ["▏" "▎" "▍" "▌" "▋" "▊" "▉"])
(def ^:private full-block "█")
(def ^:private empty-block "░")

(def ^:private ansi-reset "[0m")
(def ^:private ansi-orange "[38;5;214m")
(def ^:private ansi-red "[38;2;255;0;0m")
(def ^:private ansi-blue "[94m")
(def ^:private ansi-cyan "[96m")
(def ^:private ansi-empty-fg "[38;5;237m")

(defn supports-color?
  "True when the terminal supports ANSI colors (NO_COLOR env var unset)."
  []
  (nil? (System/getenv "NO_COLOR")))

(defn rate-fill-color
  "Threshold color for the rate-limit usage bar:
   ≥80% red, ≥60% orange, otherwise nil."
  [pct]
  (cond
    (>= pct 80) ansi-red
    (>= pct 60) ansi-orange
    :else nil))

(defn ctx-fill-color
  "Threshold color for the context-window usage bar, by absolute token count:
   ≥300k red, ≥150k orange, otherwise nil."
  [tokens]
  (cond
    (>= tokens 300000) ansi-red
    (>= tokens 150000) ansi-orange
    :else nil))

(defn percent-bar
  "Render an 8-cell horizontal bar for `pct` (0-100). When `color?`, every
   cell is a full block (filled cells in `fill-color` or default fg, empty
   cells in dim gray) producing a continuous gap-free bar at 8-level
   resolution. Without color, falls back to plain block-eighths."
  ([pct] (percent-bar pct false nil))
  ([pct color? fill-color]
   (let [pct (-> pct double (max 0.0) (min 100.0))]
     (if color?
       (let [full-cells (long (Math/round (* (/ pct 100.0) 8.0)))
             empty-cells (- 8 full-cells)
             fill (or fill-color "")
             full-cell (str fill full-block ansi-reset)
             empty-cell (str ansi-empty-fg full-block ansi-reset)]
         (str (apply str (repeat full-cells full-cell))
              (apply str (repeat empty-cells empty-cell))))
       (let [filled (long (Math/round (* (/ pct 100.0) 64.0)))
             full-cells (quot filled 8)
             partial (mod filled 8)
             partial-cells (if (pos? partial) 1 0)
             empty-cells (- 8 full-cells partial-cells)]
         (str (apply str (repeat full-cells full-block))
              (when (pos? partial) (nth partial-chars (dec partial)))
              (apply str (repeat empty-cells empty-block))))))))

(def ^:private effort-labels
  {"low" "lo" "medium" "md" "high" "hi" "xhigh" "xh" "max" "mx"})

(def ^:private effort-colors
  {"low" ansi-blue
   "medium" ansi-cyan
   ;; "high" intentionally omitted — default terminal color
   "xhigh" ansi-orange
   "max" ansi-red})

(defn format-effort
  "Render effort level as a two-letter label, suffixed with `*` when thinking
   is enabled. When `color?`, wraps the tag in a level-specific ANSI color.
   Returns nil when effort is absent."
  ([data] (format-effort data false))
  ([data color?]
   (when-let [level (get-in data [:effort :level])]
     (when-let [label (effort-labels level)]
       (let [tag (cond-> label
                   (get-in data [:thinking :enabled]) (str "*"))
             color (when color? (effort-colors level))]
         (if color
           (str color tag ansi-reset)
           tag))))))

(defn format-reset-time
  "Format a Unix epoch-seconds timestamp as 12-hour H:MM in the local zone
   (no leading zero on the hour, no AM/PM)."
  [epoch-secs]
  (when epoch-secs
    (let [zone (java.time.ZoneId/systemDefault)
          local-time (-> (java.time.Instant/ofEpochSecond (long epoch-secs))
                         (.atZone zone)
                         .toLocalTime)
          h24 (.getHour local-time)
          h12 (let [m (mod h24 12)] (if (zero? m) 12 m))]
      (format "%d:%02d" h12 (.getMinute local-time)))))

(defn format-percent-segment
  "Render a labelled bar segment like 'ct ████████'. Returns nil when
   pct is nil so the caller can omit the segment. When `color?`, the bar
   uses ANSI fg+bg for a gap-free continuous look. `fill-color` may be an
   ANSI color string for the filled portion (or nil for default)."
  ([label pct] (format-percent-segment label pct false nil))
  ([label pct color? fill-color]
   (when pct
     (str label " " (percent-bar pct color? fill-color)))))

(defn -main
  "Read stdin JSON and print one line: model + ctx bar + 5h bar."
  []
  (try
    (let [data (json/parse-string (slurp *in* :encoding "UTF-8") true)
          model-name (extract-model-name data)
          ctx-pct (get-in data [:context_window :used_percentage])
          ctx-size (get-in data [:context_window :context_window_size] 200000)
          ctx-tokens (when ctx-pct (long (* (/ (double ctx-pct) 100.0) ctx-size)))
          rate-pct (get-in data [:rate_limits :five_hour :used_percentage])
          rate-reset (format-reset-time (get-in data [:rate_limits :five_hour :resets_at]))
          color? (supports-color?)
          ctx-color (when (and color? ctx-tokens) (ctx-fill-color ctx-tokens))
          rate-color (when (and color? rate-pct) (rate-fill-color rate-pct))
          rate-segment (when rate-pct
                         (cond-> (format-percent-segment "5h" rate-pct color? rate-color)
                           rate-reset (str " " rate-reset)))
          effort-tag (format-effort data color?)
          parts (cond-> [model-name]
                  ctx-pct (conj (format-percent-segment "ct" ctx-pct color? ctx-color))
                  rate-segment (conj rate-segment)
                  effort-tag (conj effort-tag))]
      (println (str/join " " parts)))
    (catch Exception e
      (let [error-msg (or (.getMessage e) "Unknown error")]
        (println (str "Claude Code [Error: " error-msg "]"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
