#!/bin/bash

cat describe-postgres.sql | su - postgres -c "psql -U postgres -d traffic_db"
