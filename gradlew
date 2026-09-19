#!/bin/bash
# Workspace-root shim: delegate to the real Gradle wrapper inside the mederi/ repo.
cd "$(dirname "$0")/mederi" || exit 1
exec ./gradlew "$@"
