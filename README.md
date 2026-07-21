# fig-ure

Edge-native IoT monitoring and management system running on a Raspberry Pi 4B. Tracks environmental telemetry for a fig tree, handles live video streaming, and exposes a secure control interface without opening public router ports.

## Architecture

![System Architecture](design/SysDesign.png)

- **Edge Node:** Raspberry Pi 4B (ARM64) running local Clojure services.
- **Sensors:** I2C sensors reading soil moisture, air temperature, and humidity.
- **Video Pipeline:** `ffmpeg` managing webcam feed, local snapshots, and live YouTube streaming.
- **Telemetry:** Asynchronous metrics worker pushing data to InfluxDB Cloud with local disk buffering for offline resilience.
- **Networking:** Cloudflare Tunnel (`cloudflared`) providing secure HTTPS API access and SSH without port forwarding.

## Core Modules

- `fig-ure.sensors`: Asynchronous I2C sensor reader (`core.async`).
- `fig-ure.telemetry`: Background metrics worker (InfluxDB push & local buffer).
- `fig-ure.stream`: Process lifecycle manager for `ffmpeg` pipeline.
- `fig-ure.api`: Edge API gateway exposed via Cloudflare Tunnel.

## Tech Stack

- **Language:** Clojure (JDK 21)
- **Task Runner:** Babashka (`bb`)
- **Environment Management:** Nix Flakes (`flake.nix`) & `direnv` (`.envrc`)
- **Async & Concurrency:** `clojure.core.async`
- **Lifecycle:** Integrant
- **Hardware:** Raspberry Pi 4B
- **Database:** InfluxDB Cloud
- **Tunnel:** Cloudflare Tunnels
- **Video:** `ffmpeg`

## Environment Setup

### 1. Nix & direnv (Recommended)
The development environment is fully managed via Nix Flakes.

- With `direnv` installed:
  ```bash
  direnv allow
  ```
- Or enter the shell manually:
  ```bash
  nix develop
  ```

This automatically provides JDK 21, Clojure CLI, Babashka, `ffmpeg`, `cloudflared`, and `i2c-tools`.

### 2. Environment Variables
Copy the template and fill in your secrets (InfluxDB token, YouTube stream key, webcam device):
```bash
cp .env.example .env
```

## Tasks & Workflow

The project uses Babashka (`bb.edn`) as its task runner and relies on **REPL-Driven Development (RDD)**.

### Available Tasks
- `bb dev` — Start the Clojure REPL with dev dependencies (`:dev` alias).
- `bb test` — Run project test suite via Kaocha.
- `bb lint` — Static code analysis & style check via `clj-kondo`.
- `bb fmt` — Code formatting check via `cljfmt`.
- `bb fmt-fix` — Auto-fix code formatting.
- `bb outdated` — Check for outdated dependencies via `antq`.
- `bb ci` — Run full CI pipeline (linter, formatter, tests).

### REPL Workflow
1. Start REPL via `bb dev`.
2. Connect your editor (Calva / CIDER / Cursive).
3. In the `user` namespace (`dev/user.clj`), manage system lifecycle:
   - `(go)` — Start component graph.
   - `(reset)` — Reload changed code and restart component graph.
   - `(halt)` — Stop running services.

## License

Copyright (c) 2026 Alexandr Timchenko

Distributed under the MIT License. See [LICENSE](file:///home/dirge/Development/fig-ure/LICENSE) for details.
