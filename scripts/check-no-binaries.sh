#!/bin/sh
# Fails when git tracks a native binary. Binaries come from CI builds, not from
# the repository. Portable POSIX sh: runs on Linux, macOS and Git Bash on Windows.
set -eu

tracked=$(git ls-files | grep -E '\.(so|dll|dylib|lib|exp|pdb|a|o|obj|exe)$' || true)
if [ -n "$tracked" ]; then
    echo "check-no-binaries: native binaries are tracked by git:" >&2
    echo "$tracked" | sed 's/^/  /' >&2
    echo "Remove them with 'git rm --cached <file>' (CI builds them)." >&2
    exit 1
fi
echo "check-no-binaries: OK, no native binaries tracked"
