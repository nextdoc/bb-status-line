# bb-status-line

A Babashka-based statusline utility for Claude Code that provides rich visual feedback with colored braille charts, session tracking, and cost monitoring.

## Statusline Components

Here's what the statusline looks like and what each part represents:

```
Claude 3.5 Sonnet  1:30  0:45  ⢀⣠⣤⣶⣿ ⣿⣶⣤⣠⡀  $2.50
│                  │     │     │              │
│                  │     │     │              └─ Daily Cost
│                  │     │     └─ Colored Braille Chart (5-hour blocks)
│                  │     └─ Session Duration (current 5-hour window)
│                  └─ Daily Duration (since first prompt today)
└─ Model Name (color-coded: Sonnet=green, Opus=red, Haiku=cyan)
```

### Component Details

- **Model Name**: Color-coded based on Claude model type for quick visual identification
- **Daily Duration**: Time elapsed since your first prompt today (e.g., `1:30` = 1 hour 30 minutes)  
- **Session Duration**: Time elapsed in current 5-hour usage window (e.g., `0:45` = 45 minutes)
- **Braille Chart**: Visual cost history using colored Unicode braille characters
  - Each block represents a 5-hour session period
  - Colors: 🟢 Green (low cost) → 🟡 Yellow (medium) → 🔴 Red (high cost)
  - Multiple blocks show different sessions separated by gaps >5 hours
- **Daily Cost**: Your total spending since the daily baseline was established (e.g., `$2.50`)

## Features

- **Colored Braille Charts**: btop-inspired visualization using truecolor braille characters
- **Session Tracking**: Automatic detection of 5-hour Claude Code usage windows
- **Cost Monitoring**: Daily baseline tracking with hourly cost buckets
- **Model-Specific Colors**: Visual distinction between Sonnet (green), Opus (red), and Haiku (cyan)
- **Time Formatting**: Clean duration display with colon separators (e.g., `1:30`)
- **Per-Block Normalization**: Independent color scaling for each 5-hour block

## Quick Start

```bash
# Run the statusline (expects JSON input from stdin)
bb src/statusline.clj < input.json

# Run tests
bb test/statusline_test.clj
```

## Architecture

### Core Components

- **`statusline.clj`**: Main entry point with JSON parsing, state management, and output formatting
- **`braille_color.clj`**: Colored braille chart generation with green→yellow→red gradients
- **`statusline_test.clj`**: Comprehensive test suite with 100+ assertions

### Key Features

#### Braille Charts
- Uses Unicode braille characters for compact data visualization  
- Green→yellow→red color gradient based on normalized values
- Per-block normalization ensures consistent scaling within each 5-hour window
- Trailing zero truncation prevents display of future hours

#### Session Detection
- Analyzes transcript files to find 5-hour usage gaps
- Automatically determines current session boundaries
- Tracks daily and session durations independently

#### State Management
- Persistent state in `~/.claude/statusline.edn`
- Daily baseline establishment for cost tracking
- Hourly cost buckets for chart generation

## Integration with Claude Code

This statusline integrates with Claude Code's custom status line feature. To register it in your Claude Code settings:

### Option 1: Using the `/statusline` Command (Recommended)

1. Run `/statusline` in Claude Code
2. Ask Claude to set up this bb-status-line utility
3. Claude will automatically configure the settings for you

### Option 2: Manual Configuration

Add the following to your `.claude/settings.json` file:

```json
{
  "statusLine": {
    "type": "command", 
    "command": "bb /path/to/bb-status-line/src/statusline.clj",
    "padding": 0
  }
}
```

Replace `/path/to/bb-status-line/src/statusline.clj` with the actual path to your statusline script.

### JSON Input Format

Claude Code automatically passes session data as JSON via stdin. For details on the input format and available data fields, see the [Claude Code statusline documentation](https://docs.anthropic.com/en/docs/claude-code/statusline).

The status line updates every 300ms when conversation messages change, providing real-time feedback on your Claude Code session.

## Development

### Running Tests

```bash
# Run the full test suite
bb test/statusline_test.clj

# Expected output: 14 test functions, 100+ assertions
```

### Project Structure

```
bb-status-line/
├── deps.edn          # Empty deps for IDE recognition
├── src/
│   ├── statusline.clj      # Main statusline logic
│   └── braille_color.clj   # Chart generation
└── test/
    └── statusline_test.clj # Comprehensive test suite
```

## License

See LICENSE file for details.