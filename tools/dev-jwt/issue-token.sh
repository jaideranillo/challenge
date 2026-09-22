#!/usr/bin/env bash
# Mints a local RS256 test JWT signed with tools/dev-jwt/dev-private.pem.
#
# PUBLICLY KNOWN TEST KEY - NO SECURITY VALUE. This script and the key pair
# next to it exist only for local development (ADR-007 SS7). No IdP, no
# network, no container is used or required. Production and staging never
# trust this key: they validate only against JWKS from the corporate IdP.
#
# Usage:
#   issue-token.sh --client-id CLIENT001 --scope "notifications:read"
#   issue-token.sh --client-id CLIENT001 --scope "notifications:read notifications:replay"
#
# Negative-test flags (TASK-008-20 / TASK-008-28):
#   --wrong-audience     sign with an audience that does not match the configured one
#   --expired            sign a token whose exp is already in the past
#   --omit-client-id     do not include the client_id claim at all
#
# Prints the token, and only the token, on stdout.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRIVATE_KEY="${SCRIPT_DIR}/dev-private.pem"

ISSUER="http://localhost:8080/issuer/challenge-local"
AUDIENCE="challenge-api"
MAX_LIFETIME_SECONDS=3600

client_id=""
scope=""
subject=""
lifetime_seconds="${MAX_LIFETIME_SECONDS}"
wrong_audience=false
expired=false
omit_client_id=false

usage() {
  echo "Usage: $0 --client-id CLIENT_ID --scope \"SCOPE [SCOPE...]\" [--subject SUBJECT] [--lifetime SECONDS] [--wrong-audience] [--expired] [--omit-client-id]" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --client-id)
      client_id="$2"; shift 2 ;;
    --scope)
      scope="$2"; shift 2 ;;
    --subject)
      subject="$2"; shift 2 ;;
    --lifetime)
      lifetime_seconds="$2"; shift 2 ;;
    --wrong-audience)
      wrong_audience=true; shift ;;
    --expired)
      expired=true; shift ;;
    --omit-client-id)
      omit_client_id=true; shift ;;
    -h|--help)
      usage ;;
    *)
      echo "Unknown argument: $1" >&2
      usage ;;
  esac
done

if [[ "${omit_client_id}" != "true" && -z "${client_id}" ]]; then
  echo "Error: --client-id is required (a token without it is exactly what the decoder must reject)" >&2
  exit 1
fi

if (( lifetime_seconds > MAX_LIFETIME_SECONDS )); then
  echo "Error: --lifetime cannot exceed ${MAX_LIFETIME_SECONDS} seconds (ADR-007 SS3 ceiling)" >&2
  exit 1
fi

if [[ -z "${subject}" ]]; then
  subject="${client_id:-unknown-subject}"
fi

now=$(date +%s)
if [[ "${expired}" == "true" ]]; then
  iat=$(( now - lifetime_seconds - 60 ))
  exp=$(( iat + lifetime_seconds ))
else
  iat="${now}"
  exp=$(( iat + lifetime_seconds ))
fi

audience="${AUDIENCE}"
if [[ "${wrong_audience}" == "true" ]]; then
  audience="wrong-audience-$(date +%s)"
fi

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

header='{"alg":"RS256","typ":"JWT"}'

payload="{\"iss\":\"${ISSUER}\",\"aud\":\"${audience}\",\"exp\":${exp},\"iat\":${iat},\"sub\":\"${subject}\""
if [[ "${omit_client_id}" != "true" ]]; then
  payload="${payload},\"client_id\":\"${client_id}\""
fi
if [[ -n "${scope}" ]]; then
  payload="${payload},\"scope\":\"${scope}\""
fi
payload="${payload}}"

header_b64=$(printf '%s' "${header}" | base64url)
payload_b64=$(printf '%s' "${payload}" | base64url)
signing_input="${header_b64}.${payload_b64}"

signature_b64=$(printf '%s' "${signing_input}" | openssl dgst -sha256 -sign "${PRIVATE_KEY}" | base64url)

printf '%s.%s\n' "${signing_input}" "${signature_b64}"
