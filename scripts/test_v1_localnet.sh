#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

address="${TOS_TEST_ADDRESS:-UQCJFahawZUzYka4uzFTeWns-oQNfoa0VNVOAn8e8BJnXPZe}"
control="${TOS_TEST_CONTROL:-http://127.0.0.1:18745}"
rpc_ports=(18545 18546 18547)

rpc() {
  local port="$1" method="$2" params="$3"
  curl --fail --silent --show-error --max-time 10 \
    -H 'Content-Type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" \
    "http://127.0.0.1:${port}/jsonRPC"
}

curl --fail --silent --show-error --max-time 5 "$control/readyz" | jq -e '.ok == true' >/dev/null

for port in "${rpc_ports[@]}"; do
  rpc "$port" getMasterchainInfo '{}' | jq -e '.ok == true and .result.last.seqno > 0' >/dev/null
done

converged=false
for _ in {1..30}; do
  heads=()
  for port in "${rpc_ports[@]}"; do
    heads+=("$(rpc "$port" getMasterchainInfo '{}' | jq -r '.result.last | "\(.seqno):\(.root_hash):\(.file_hash)"')")
  done
  if [[ "${heads[0]}" == "${heads[1]}" && "${heads[1]}" == "${heads[2]}" ]]; then
    converged=true
    break
  fi
  sleep 1
done
[[ "$converged" == true ]] || { echo 'v1-localnet: validators did not converge' >&2; exit 1; }

params="$(jq -cn --arg address "$address" '{address:$address}')"
before="$(rpc 18545 getAddressInformation "$params" | jq -er '.result.balance | tonumber')"
curl --fail --silent --show-error --max-time 65 \
  -H 'Content-Type: application/json' \
  --data "$(jq -cn --arg address "$address" '{address:$address,amount:1}')" \
  "$control/transfer" | jq -e '(.after | tonumber) > (.before | tonumber)' >/dev/null

replicated=false
for _ in {1..45}; do
  balances=()
  for port in "${rpc_ports[@]}"; do
    balances+=("$(rpc "$port" getAddressInformation "$params" | jq -er '.result.balance | tonumber')")
  done
  if (( balances[0] > before )) && [[ "${balances[0]}" == "${balances[1]}" && "${balances[1]}" == "${balances[2]}" ]]; then
    replicated=true
    break
  fi
  sleep 1
done
[[ "$replicated" == true ]] || { echo 'v1-localnet: transfer did not replicate to all validators' >&2; exit 1; }

history_params="$(jq -cn --arg address "$address" '{address:$address,limit:5}')"
first_page="$(rpc 18545 getTransactions "$history_params")"
first_page_count="$(jq -r '.result | length' <<<"$first_page")"
(( first_page_count == 5 )) || { echo 'v1-localnet: expected a full first history page' >&2; exit 1; }
cursor_lt="$(jq -er '.result[-1].transaction_id.lt' <<<"$first_page")"
cursor_hash="$(jq -er '.result[-1].transaction_id.hash' <<<"$first_page")"
next_params="$(jq -cn --arg address "$address" --arg lt "$cursor_lt" --arg hash "$cursor_hash" \
  '{address:$address,limit:5,lt:$lt,hash:$hash}')"
second_page="$(rpc 18545 getTransactions "$next_params")"
second_page_count="$(jq -r '.result | length' <<<"$second_page")"
(( second_page_count > 1 )) || { echo 'v1-localnet: expected a second history page' >&2; exit 1; }
duplicate_count="$(jq -n --argjson first "$(jq '[.result[].transaction_id.hash]' <<<"$first_page")" \
  --argjson second "$(jq '[.result[].transaction_id.hash]' <<<"$second_page")" \
  '$first - ($first - $second) | length')"
(( duplicate_count == 1 )) || { echo 'v1-localnet: history pages contain unexpected duplicates' >&2; exit 1; }
jq -e --arg lt "$cursor_lt" \
  '[.result[1:][].transaction_id.lt | tonumber] | all(. < ($lt | tonumber))' <<<"$second_page" >/dev/null

TOS_TEST_ADDRESS="$address" TOS_TEST_RPC='http://127.0.0.1:18545/jsonRPC' \
  ./gradlew :apps:wallet:api:testDebugUnitTest \
  --tests 'network.tos.wallet.api.tos.TosEventMapperLocalNodeTest'

echo "v1-localnet: PASS (3 validators, transfer replication, cursor pagination, history mapping)"
