#!/usr/bin/env bash
# Lot 8 (dette du lot 6) : les bibliothèques natives liées à l'app iOS (LiteRT-LM, SQLCipher) n'appellent aucune API
# réseau. Lit les symboles non définis (`nm -u`) de leur tranche iOS arm64 dans les paquets résolus par Xcode, et
# liste leurs manifestes de confidentialité. À lancer sur macOS après `xcodebuild` (CI, job iOS).
# Usage : tools/check-native-network.sh [DerivedData]   (exit 1 en cas de violation)
set -euo pipefail
DERIVED="${1:-$HOME/Library/Developer/Xcode/DerivedData}"
NETWORK='^_(getaddrinfo|gethostbyname|connect|socket|nw_[a-z_]+|CFSocket[A-Za-z]*|CFStream[A-Za-z]*Socket[A-Za-z]*|CFNetwork[A-Za-z]*)$|NSURLSession|OBJC_CLASS_\$_NSURLConnection'
status=0
found=0

while IFS= read -r framework; do
  name=$(basename "$framework" .xcframework)
  slice=$(find "$framework" -maxdepth 1 -type d -name "ios-arm64" | head -1)
  [ -z "$slice" ] && { echo "INFO: $name sans tranche ios-arm64"; continue; }
  binary=$(find "$slice" -type f \( -perm -u+x -o -name "*.a" \) ! -name "*.plist" ! -name "*.h" | head -1)
  [ -z "$binary" ] && { echo "INFO: $name : binaire introuvable"; continue; }
  found=$((found + 1))
  hits=$(nm -u "$binary" 2>/dev/null | awk '{print $NF}' | grep -E "$NETWORK" | sort -u || true)
  if [ -n "$hits" ]; then
    echo "VIOLATION: $name appelle des API réseau :"
    printf '  %s\n' $hits
    status=1
  else
    echo "OK: $name ($(basename "$binary")) : aucun symbole réseau"
  fi
  privacy=$(find "$framework" -name PrivacyInfo.xcprivacy | head -1)
  if [ -n "$privacy" ]; then
    echo "  manifeste de confidentialité : présent"
  else
    echo "  manifeste de confidentialité : absent (à déclarer dans celui de l'app si une API à raison est utilisée)"
  fi
done < <(find "$DERIVED" -path "*SourcePackages/artifacts*" -name "*.xcframework" -type d 2>/dev/null \
  | grep -iE 'litert|sqlcipher' | sort -u)

if [ "$found" -lt 2 ]; then
  echo "VIOLATION: LiteRT-LM et SQLCipher attendus, $found bibliothèque(s) trouvée(s) sous $DERIVED"
  status=1
fi
exit $status
