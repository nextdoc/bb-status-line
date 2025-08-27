# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

bb-status-line is a Babashka-based statusline utility designed for Claude Code integration. It provides rich visual feedback through colored braille charts, session tracking, and cost monitoring. The system uses truecolor braille characters with green→yellow→red gradients for data visualization.

## Commands

### Running the Application
```bash
# Run the statusline (expects JSON input from stdin)
bb src/statusline.clj < input.json
```

### Testing
```bash
# Run the comprehensive test suite
bb test/statusline_test.clj

# Expected output: 14 test functions with 100+ assertions
```

## Architecture

### Core System Design

The statusline operates as a data pipeline with these key stages:

1. **JSON Input Processing** - Parses Claude Code data format containing model info, cost, and transcript paths
2. **State Management** - Persistent state in `~/.claude/statusline.edn` for tracking daily baselines and hourly metrics
3. **Session Detection** - Analyzes transcript files to identify 5-hour usage windows and session boundaries
4. **Cost Tracking** - Daily baseline establishment with hourly cost buckets for visualization
5. **Visualization** - Colored braille charts with per-block normalization for independent scaling

### Key Components

**`src/statusline.clj`** (655 lines) - Main entry point containing:
- JSON parsing and data extraction functions
- State persistence with load/save operations
- Time/date calculations and session boundary detection
- Daily cost baseline tracking and hourly chart updates
- Duration formatting and ANSI color management
- Main pipeline orchestration in `-main` function

**`src/braille_color.clj`** (118 lines) - Colored visualization engine:
- Truecolor braille character generation using Unicode range 0x2800+
- Green→yellow→red gradient mapping based on normalized values
- 5-hour block segmentation with per-block normalization
- Multi-block chart rendering with space separators
- Trailing zero truncation to avoid displaying future hours

**`test/statusline_test.clj`** (565 lines) - Comprehensive test coverage:
- Data parsing, time handling, and color formatting tests
- Cost tracking and session detection validation
- Braille chart generation and color gradient verification
- Multi-block segmentation and integration tests
- Regression tests for critical bug fixes

### Data Flow Architecture

1. **Input**: JSON from Claude Code containing `{model, cost, transcript_path, exceeds_200k_tokens}`
2. **State Loading**: Read persistent state from `~/.claude/statusline.edn`
3. **Session Analysis**: Parse JSONL transcript to detect 5-hour session boundaries
4. **Cost Processing**: Update daily baseline and calculate today's cost delta
5. **Hourly Tracking**: Update hourly cost buckets when crossing hour boundaries
6. **Visualization**: Generate colored braille charts with 5-hour block segmentation
7. **Output**: Formatted statusline: `model daily-duration session-duration hourly-chart cost`

### Critical Implementation Details

**Session Boundary Detection**: Finds gaps >5 hours between transcript messages to identify current session start, matching Claude Code's 5-hour usage windows.

**Per-Block Normalization**: Each 5-hour block in the chart normalizes independently, ensuring consistent color scaling within each session block rather than global normalization.

**Trailing Zero Truncation**: Charts only display hours up to the last non-zero data point, preventing visualization of future hours.

**Color Mapping**: 
- Sonnet models: bright green (`\u001b[92m`)
- Opus models: bright red (`\u001b[38;2;255;0;0m`)
- Haiku models: cyan (`\u001b[36m`)
- Chart gradients: green (low) → yellow (mid) → red (high)

### State File Format

```clojure
{:today-first-prompt-time 1756247504518
 :daily-costs {:date "2025-08-27" :baseline 0.88}
 :current-total-cost 1.74
 :hourly-costs {:date "2025-08-27" :buckets {8 0.0, 9 3.73, 10 5.28}}
 :last-recorded-hour 12}
```

### Expected JSON Input Format

```json
{
  "model": {"display_name": "Claude 3.5 Sonnet"},
  "cost": {"total_cost_usd": "2.50"},
  "transcript_path": "/path/to/transcript.jsonl",
  "exceeds_200k_tokens": false
}
```

## Development Notes

- The codebase uses functional programming patterns with pure functions and immutable data structures
- All time calculations use Java interop (`java.time.*`) for timezone-aware processing
- ANSI color support is conditional based on `NO_COLOR` environment variable
- The test suite includes regression tests for critical bugs (color inversion, braille pairing)
- Empty `deps.edn` file exists solely for IDE recognition - no external dependencies required