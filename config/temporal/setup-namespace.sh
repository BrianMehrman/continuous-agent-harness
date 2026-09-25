#!/bin/sh
set -eu
if temporal --address temporal:7233 operator namespace describe --namespace harness; then
    exit 0
fi
temporal --address temporal:7233 operator namespace create --namespace harness --retention 168h
