# FERRETCANNON Complement Testing Dashboard

This directory contains the static website for the FERRETCANNON Complement testing dashboard, hosted at [areweyolosynapseyet.roflcopter.wtf](https://areweyolosynapseyet.roflcopter.wtf).

## Overview

The dashboard displays real-time Matrix Complement compliance testing results for the FERRETCANNON Matrix homeserver implementation. It fetches test data from the `complement-badges` branch, which is automatically updated by the parallel Complement workflow.

## Files

- `index.html` - Main HTML structure
- `styles.css` - Responsive CSS styling with dark theme
- `script.js` - JavaScript for fetching and displaying test results
- `CNAME` - Custom domain configuration

## How It Works

1. The [Complement parallel workflow](../.github/workflows/complement-parallel.yml) runs all test suites
2. Test results are aggregated and stored as JSON in the `complement-badges` branch
3. This static site fetches the JSON data from the branch
4. Results are displayed with colour-coded pass rates and detailed statistics
5. The page auto-refreshes every 5 minutes to show latest results

## Data Source

The dashboard reads from:
```
https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/test-results.json
```

This JSON file contains:
- Overall statistics (passed, failed, skipped, total, percentage)
- Per-suite breakdowns for all 15 test suites
- Colour coding based on pass rate

## Local Development

To test locally:

```bash
# Serve the docs directory with any static file server
python -m http.server 8000 --directory docs
# or
npx serve docs
```

Then visit `http://localhost:8000`

## Deployment

The site is automatically deployed to GitHub Pages via the [pages-deploy workflow](../.github/workflows/pages-deploy.yml) when changes are pushed to the `docs/` directory on the main branch.

### DNS Configuration

To use the custom domain `areweyolosynapseyet.roflcopter.wtf`, configure the following DNS records:

**Option 1: CNAME (Recommended)**
```
Type: CNAME
Name: areweyolosynapseyet
Value: edgeraghty.github.io
```

**Option 2: A Records**
```
Type: A
Name: areweyolosynapseyet
Value: 185.199.108.153
Value: 185.199.109.153
Value: 185.199.110.153
Value: 185.199.111.153
```

## Features

- 🎯 Real-time test results from Complement suite
- 📊 Overall compliance percentage and statistics
- 🎨 Colour-coded pass rates (green = excellent, red = needs work)
- 📱 Fully responsive design
- 🌙 Dark mode theme
- ♻️ Auto-refresh every 5 minutes
- 🔗 Direct links to workflow runs and GitHub repo

## Big Up the FERRETCANNON Massive! 🎆

Shoutout to all the contributors making this Matrix server implementation happen!
