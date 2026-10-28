#!/bin/sh
# Checks that the JNI symbols exported by native libraries match the `native`
# methods declared in the Java sources.
#
# usage: verify-jni-symbols.sh <natives-dir> <java-src-dir>...
#   REQUIRE_LIBRARIES=1   fail when no library is found (use in release gates)
#
# Expected symbol for a native method m of class C in package p:
#   Java_<p with '.' -> '_'>_<C>_<m>      ('_' in a name becomes '_1')
# Every directory under <natives-dir> that holds libraries is one platform. Each
# platform must export every expected symbol, and no Java_* symbol that no
# Java source declares (a stale prefix, a renamed package, or a class that moved
# fails here). Overloaded native methods are not supported: they need JNI
# signature suffixes this check does not model, so they fail too.
#
# Tools, by library format: nm (ELF), llvm-nm (Mach-O: GNU nm cannot read it),
# objdump (PE export table: nm sees no exports in a DLL without a COFF symbol
# table), plus POSIX sh, awk, sed, grep, sort, comm, find.
set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <natives-dir> <java-src-dir>..." >&2
    exit 2
fi
natives=$1
shift

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# 1. Expected symbols, from the native method declarations in the Java sources.
: > "$tmp/declared"
for root in "$@"; do
    [ -d "$root" ] || continue
    find "$root" -name '*.java' | sort | while read -r file; do
        pkg=$(sed -n 's/^package[[:space:]][[:space:]]*\([A-Za-z0-9_.]*\)[[:space:]]*;.*/\1/p' "$file" | head -n 1)
        cls=$(basename "$file" .java)
        mangled_pkg=$(printf '%s' "$pkg" | sed 's/_/_1/g; s/\./_/g')
        if [ -n "$mangled_pkg" ]; then prefix="Java_${mangled_pkg}_${cls}"; else prefix="Java_${cls}"; fi
        # A declaration: optional modifiers, `native`, return type, name, `(` -- at the
        # start of the line, so comments and string literals never match.
        grep -E '^[[:space:]]*((public|private|protected|static|final|synchronized|abstract)[[:space:]]+)*native[[:space:]]+[][A-Za-z0-9_<>,. ]+[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\(' "$file" \
            | sed -E 's/^(.*[^A-Za-z0-9_])?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*\(.*$/\2/' \
            | awk -v p="$prefix" 'NF { print p "_" $0 }'
    done
done >> "$tmp/declared"

if [ -s "$tmp/declared" ]; then
    dups=$(sort "$tmp/declared" | uniq -d)
    if [ -n "$dups" ]; then
        echo "verify-jni-symbols: overloaded native methods are not supported:" >&2
        echo "$dups" | sed 's/^/  /' >&2
        exit 1
    fi
fi
sort -u "$tmp/declared" > "$tmp/expected"
expected_count=$(wc -l < "$tmp/expected" | tr -d ' ')

# 2. Libraries, grouped by directory (one directory = one platform).
find "$natives" -type f \( -name '*.so' -o -name '*.so.[0-9]*' -o -name '*.dylib' -o -name '*.dll' \) 2>/dev/null | sort > "$tmp/libs"
if [ ! -s "$tmp/libs" ]; then
    if [ "${REQUIRE_LIBRARIES:-0}" = "1" ]; then
        echo "verify-jni-symbols: no native library under $natives (REQUIRE_LIBRARIES=1)" >&2
        exit 1
    fi
    echo "verify-jni-symbols: no native library under $natives; nothing to check ($expected_count expected symbols)"
    exit 0
fi

if ! command -v nm >/dev/null 2>&1; then
    echo "verify-jni-symbols: nm not found; cannot read exported symbols" >&2
    exit 2
fi
llvm_nm=
for tool in llvm-nm llvm-nm-*; do
    if command -v "$tool" >/dev/null 2>&1; then llvm_nm=$tool; break; fi
done
if grep -q '\.dylib$' "$tmp/libs" && [ -z "$llvm_nm" ]; then
    echo "verify-jni-symbols: llvm-nm not found; needed to read Mach-O (.dylib)" >&2
    exit 2
fi
if grep -q '\.dll$' "$tmp/libs" && ! command -v objdump >/dev/null 2>&1; then
    echo "verify-jni-symbols: objdump not found; needed to read PE exports (.dll)" >&2
    exit 2
fi

exports_of() {
    case $1 in
        *.dylib) "$llvm_nm" -gU "$1" | awk 'NF >= 2 { print $NF }' | sed 's/^_//' ;;
        *.dll)   objdump -p "$1" | awk '/^\t\[ *[0-9]+\] / { sub(/^[^\]]*\] */, ""); print $1 }' ;;
        *)       nm -D --defined-only "$1" | awk 'NF >= 2 && $(NF-1) ~ /^[TtWw]$/ { print $NF }' ;;
    esac
}

failures=0
platforms=0
sed 's|/[^/]*$||' "$tmp/libs" | sort -u > "$tmp/platforms"
while read -r dir; do
    platforms=$((platforms + 1))
    : > "$tmp/exported"
    while read -r lib; do
        case $lib in "$dir"/*) ;; *) continue ;; esac
        exports_of "$lib" | grep '^Java_' >> "$tmp/exported" || true
    done < "$tmp/libs"
    sort -u "$tmp/exported" > "$tmp/exported.sorted"

    missing=$(comm -23 "$tmp/expected" "$tmp/exported.sorted")
    stale=$(comm -13 "$tmp/expected" "$tmp/exported.sorted")
    count=$(wc -l < "$tmp/exported.sorted" | tr -d ' ')
    if [ -z "$missing" ] && [ -z "$stale" ]; then
        echo "verify-jni-symbols: OK   $dir ($count exported, $expected_count expected)"
        continue
    fi
    failures=$((failures + 1))
    echo "verify-jni-symbols: FAIL $dir" >&2
    if [ -n "$missing" ]; then
        echo "  missing (declared in Java, not exported):" >&2
        echo "$missing" | sed 's/^/    /' >&2
    fi
    if [ -n "$stale" ]; then
        echo "  stale (exported, not declared in any Java source):" >&2
        echo "$stale" | sed 's/^/    /' >&2
    fi
done < "$tmp/platforms"

if [ "$failures" -gt 0 ]; then
    echo "verify-jni-symbols: $failures of $platforms platform(s) do not match" >&2
    exit 1
fi
echo "verify-jni-symbols: all $platforms platform(s) match"
