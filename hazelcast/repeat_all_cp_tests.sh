#!/usr/bin/env bash

tests=("non-reentrant-lock" "reentrant-lock" "non-reentrant-fenced-lock" "reentrant-fenced-lock" "semaphore" "id-gen-long" "cas-long" "cas-reference")

if [ $# -lt 3 ]; then
	echo "Usage: ./repeat_all_cp_tests.sh repeat test_duration license [tests...]"
	echo "Tests: ${tests[*]}"
	exit 1
fi

repeat=$1
test_duration=$2
license=$3

if [ $# -gt 3 ]; then
  # Just run specified tests...
  tests=()
  for i in "${@:4}"
  do
    tests+=("$i")
  done
fi

run_single_test () {
    test_name=$1
    nemesis=$2
    persistent=$3
    echo "Running '$test_name' test with '$nemesis' nemesis, persistent=$persistent"

    lein run test --workload "${test_name}" --time-limit "${test_duration}" --license "${license}" --nemesis "${nemesis}" --persistent "${persistent}"

    if [ $? != '0' ]; then
        echo "'$test_name' test failed"
        exit 1
    fi
}

round=1
echo "Will run [${tests[*]}] tests..."

while [ ${round} -le ${repeat} ]; do

    echo "round: $round"

    for test in "${tests[@]}"
    do
      run_single_test "${test}" "partition" "false"
      run_single_test "${test}" "partition" "true"
      run_single_test "${test}" "restart-majority" "true"
    done

    ((round++))

done
