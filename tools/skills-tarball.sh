#!/usr/bin/env bash
# v2.22 (#161) Pack the OmarchyTV agent skills into one archive for a release:
#   tools/skills-tarball.sh 2.22.0   ->   out/omarchytv-skills-2.22.0.tar.gz
# Contents: omarchytv-setup/ and omarchytv/ (each with its SKILL.md), plus README.md. Nothing is uploaded.
set -euo pipefail
cd "$(dirname "$0")/.."
ver="${1:?usage: tools/skills-tarball.sh VERSION}"
[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)?$ ]] || { echo "bad version: $ver" >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/omarchytv-skills" out
cp -r skills/omarchytv-setup docs/examples/hermes-skill/omarchytv "$tmp/omarchytv-skills/"
cp skills/README.md "$tmp/omarchytv-skills/README.md"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-01-01 00:00Z' \
    -C "$tmp" -czf "out/omarchytv-skills-$ver.tar.gz" omarchytv-skills
sha256sum "out/omarchytv-skills-$ver.tar.gz"
