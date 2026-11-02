#!/bin/bash
# Runs the whole suite against the mock TV (needs python3 and ffmpeg).
set -e
cd "$(dirname "$0")"
exec python3 -W ignore::ResourceWarning -m unittest test_crypto test_e2e test_ai test_assistant test_mirror test_cast test_agents test_stats test_grokbot test_theme_set test_liftoff_power test_living test_omastorm "$@"
