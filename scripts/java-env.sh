#!/usr/bin/env bash
# Source this file. Picks JDK 21 for Maven when JAVA_HOME isn't set.
# Homebrew's openjdk@21 is "keg-only": it isn't registered with macOS's
# /usr/libexec/java_home, so we look for it explicitly first.
_hh_jdk21_brew=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  if [ -x "$_hh_jdk21_brew/bin/java" ]; then
    export JAVA_HOME="$_hh_jdk21_brew"
  elif JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME="$JH"
  else
    echo "java-env: JDK 21 not found (brew install openjdk@21)" >&2
    return 1 2>/dev/null || exit 1
  fi
fi
unset _hh_jdk21_brew
