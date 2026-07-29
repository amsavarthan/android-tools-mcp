#!/usr/bin/env bash
# Release script for android-tools-mcp.
#
# Usage:
#   ./scripts/release.sh <version>
#
# Example:
#   ./scripts/release.sh 0.1.0
#
# Requirements:
#   - Android Studio installed (for the build)
#   - gh CLI installed and authenticated with repo write access
#   - Clean git working tree

set -euo pipefail

VERSION="${1:-}"

# ---- Validate ---------------------------------------------------------------

if [[ -z "$VERSION" ]]; then
  echo "Usage: ./scripts/release.sh <version>" >&2
  echo "Example: ./scripts/release.sh 1.2.0" >&2
  exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version must be in semver format (e.g. 1.2.0)" >&2
  exit 1
fi

if ! command -v jenv &>/dev/null; then
  echo "Warning: jenv not found. Make sure JAVA_HOME points to JDK 21." >&2
fi

if ! command -v gh &>/dev/null; then
  echo "Error: gh CLI is not installed. Install from https://cli.github.com" >&2
  exit 1
fi

if ! gh auth status &>/dev/null; then
  echo "Error: not authenticated with gh. Run: gh auth login" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Error: working tree is not clean. Commit or stash your changes first." >&2
  exit 1
fi

TAG="v${VERSION}"

if git rev-parse "$TAG" &>/dev/null; then
  echo "Error: tag $TAG already exists." >&2
  exit 1
fi

# ---- Bump version -----------------------------------------------------------

echo "Bumping version to $VERSION..."
sed -i "" "s/^plugin\.version = .*/plugin.version = $VERSION/" gradle.properties

# ---- Build ------------------------------------------------------------------

echo "Building plugin..."
if command -v jenv &>/dev/null; then
  jenv exec ./gradlew buildPlugin --quiet
else
  ./gradlew buildPlugin --quiet
fi

ZIP=$(find build/distributions -name "*.zip" | head -1)
if [[ -z "$ZIP" ]]; then
  echo "Error: build succeeded but no ZIP found in build/distributions/" >&2
  exit 1
fi

# ---- Commit + tag -----------------------------------------------------------

echo "Committing version bump..."
git add gradle.properties
if git diff --cached --quiet; then
  echo "Version already at $VERSION, skipping commit."
else
  git commit -m "chore: release $TAG"
fi

echo "Tagging $TAG..."
git tag "$TAG"

echo "Pushing..."
git push origin main
git push origin "$TAG"

# ---- GitHub release ---------------------------------------------------------

echo "Creating GitHub release..."
gh release create "$TAG" "$ZIP" \
  --title "$TAG" \
  --notes "Install the plugin ZIP via **Settings → Plugins → Install Plugin from Disk** in Android Studio." \
  --latest

echo ""
echo "Released $TAG"
echo "$(gh release view "$TAG" --json url -q .url)"
