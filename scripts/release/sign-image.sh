#!/usr/bin/env bash
# sign-image.sh IMAGE:TAG: signs a pushed image's manifest list with cosign, keyless (the GitHub
# Actions OIDC identity of the workflow that runs it, recorded in Sigstore's transparency log), and
# writes each platform's compressed size (the sum of its layers in the registry) to the step summary.
# Run in a job with `permissions: id-token: write`, after docker login and sigstore/cosign-installer.
#
# Verify a published image with:
#   cosign verify starlakeai/quack-on-demand:<tag> \
#     --certificate-oidc-issuer https://token.actions.githubusercontent.com \
#     --certificate-identity-regexp '^https://github.com/starlake-ai/(qod|quack-on-demand)/\.github/workflows/(release|snapshot)\.yml@'
# (the node image's snapshots are signed by quack-node-snapshot.yml: `(release|quack-node-snapshot)`).
set -euo pipefail
ref="${1:?usage: sign-image.sh IMAGE:TAG}"
repo="${ref%:*}"
digest="$(docker buildx imagetools inspect "$ref" --format '{{json .Manifest}}' | jq -r .digest)"
[[ "$digest" == sha256:* ]] || { echo "sign-image: no digest for $ref" >&2; exit 1; }
cosign sign --yes "$repo@$digest"
echo "sign-image: signed $repo@$digest ($ref)"

summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
{
  echo "### $ref"
  echo
  echo "Signed: \`$repo@$digest\`"
  echo
  echo "| Platform | Compressed |"
  echo "|---|---|"
  docker buildx imagetools inspect --raw "$ref" |
    jq -r '.manifests[] | select(.platform.os == "linux") | "\(.platform.os)/\(.platform.architecture) \(.digest)"' |
    while read -r platform d; do
      bytes="$(docker buildx imagetools inspect --raw "$repo@$d" | jq '([.layers[].size] | add) + .config.size')"
      echo "| $platform | $(( bytes / 1000000 )) MB ($bytes bytes) |"
    done
  echo
} >> "$summary"
