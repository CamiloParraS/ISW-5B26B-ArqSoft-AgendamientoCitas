---
title: Build: demo UI
type: task
status: closed
blocked_by: [12-build-clinical-and-audit]
assignee: Camilo Parra
---

## Question

The vanilla HTML/JS console from "Demo UI shape (prototype)", embedded in `cmd/clinic`, plus the `/demo/*` endpoints (clock, provider proxy, burst, crash) behind `DEMO_MODE`. Done when every step of the report's demo script can be driven from the browser, except running `go test`.

## Resolution

2026-11-11. Built `web/{index.html,styles.css,app.js}`, embedded with `go:embed` and served at `/`. A top bar (health pill, demo clock, Tick now, provider switch, Signed in as), 8 screens following the demo script, and Patient and Doctor views. Demo endpoints `/demo/{clock,tick,provider,burst,crash,messages,appointments}` sit behind `DEMO_MODE`. Verified through the same API calls the screens make: burst, provider outage and recovery, clinical 200/403 with the access log, crash recovery in about 1.2 s with no appointments lost, and the sweep. README.md documents the walkthrough.
