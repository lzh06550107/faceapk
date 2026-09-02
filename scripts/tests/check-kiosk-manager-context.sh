#!/usr/bin/env bash
set -euo pipefail
file="${1:-app/src/main/java/com/punch/app/utils/KioskManager.java}"
python3 - "$file" <<'PY'
import re, sys
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
m=re.search(r'private static void enterIfPossible\(Activity activity, int attempt\) \{(.*?)\n    \}', s, re.S)
if not m:
    print('FAIL: enterIfPossible(Activity,int) not found')
    sys.exit(1)
body=m.group(1)
if 'ensureOwnerKioskPolicies(context);' in body:
    print('FAIL: enterIfPossible references undefined context')
    sys.exit(1)
if 'ensureOwnerKioskPolicies(activity);' not in body:
    print('FAIL: enterIfPossible does not apply owner policy with activity context')
    sys.exit(1)
print('PASS: KioskManager enterIfPossible uses activity context')
PY
