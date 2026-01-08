#!/bin/bash

cat <<EOF | su - hbase -c "hbase shell"
describe 'traffic_data'
scan 'traffic_data', {LIMIT => 5}
EOF
