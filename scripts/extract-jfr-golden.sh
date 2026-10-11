#!/usr/bin/env bash
# Copy curated golden chunk pairs into src/test/resources/jfr-fixtures/chunks/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/src/test/resources/jfr-fixtures/chunks"
SOURCE="${1:-${ROOT}/../do_not_commit/chunks}"

if [[ ! -d "${SOURCE}" ]]; then
  echo "Source chunk directory not found: ${SOURCE}" >&2
  echo "Usage: $0 [path-to-chunks-dir]" >&2
  exit 1
fi

STEMS=(
  44e402665f61c3e2fdf630f3d4b19b35fa84a3eab5c7a3cb1e57ed2a0960e24b
  3b4f3b9b0acbb838cb126cbfe9a6d1601be561c1de4b26d3066fd59ef3089bae
  42b8ad8655ebee674c9d96848059964d3888390326dc1e950b0035c417f441c0
  38a2bf4079be15b29d31b2f3c91039b805a27018bed13454a64c19ea9f51f0f0
  ce5d0b159d2b1df57747e9d0a7b5cfa1d84a43bdd7d0e3ccd715275e6bb296f6
  c2dd45926fbdefc3508139e8a953cd6ba81d315d0d3c443eb695caac2f757fbd
  d7f763d621f3398b2cb1386244c232746982c026a4cc7cdd459282a1437cf1a8
  db7959c1713019a65031bf9fd3a939e43b9913e368deda0bd1872a38b1acc7dd
  b05b2ace4da2850b8f39bf9da049ab10d150ed5c3d5db1a5946b3e2ef71f130c
  ae7f5431f3b530958ec2da1ce4a6fb82a2c0d5e7dbf65a8714e13f2253452dc5
  3ac6d4255b74df29ac0c016eec45008bbddea0b50a90c7ec6ab5e3674ad26a83
  4c88f571bf8ac07d31b5c3be9f827618409fa16c49e4fa100bbe04491d009ea1
  ac50ad46aefc789ee2d7d240ff7461a79bca066e9d01850715f0017c7b9e756b
  9d6d414256307c4f59a3598a49715c76cd6fcc85f31dd10cfee98dd3f1119e74
  00044f5ca5f798ff6778a9dac78f29ee3903ff5bf74d33c1b2dd6194234c0709
)

mkdir -p "${DEST}"
copied=0
for stem in "${STEMS[@]}"; do
  for ext in jfr meta; do
    src="${SOURCE}/${stem}.${ext}"
    if [[ ! -f "${src}" ]]; then
      echo "Missing ${src}" >&2
      exit 1
    fi
    cp "${src}" "${DEST}/"
    copied=$((copied + 1))
  done
done
echo "Copied ${copied} files to ${DEST}"
