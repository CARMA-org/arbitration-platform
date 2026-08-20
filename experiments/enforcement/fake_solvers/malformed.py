#!/usr/bin/env python3
"""A solver stand-in that emits malformed (non-JSON) output on stdout."""
import sys

sys.stdin.read()
sys.stdout.write("this is not json { broken ]\n")
