#!/bin/bash

hbase shell <<EOF
create 'traffic', 'd'
describe 'traffic'
EOF
