import argparse
import subprocess
import sys
import os
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    flag = ["--smoke"] if args.smoke else []
    steps = [
        ["run_rounding_comparison.py"],
        ["run_experiment1.py"],
        ["run_experiment2.py"],
        ["run_experiment3.py"],
        ["run_experiment4.py"],
        ["run_experiment5.py"],
        ["figures.py"],
    ]
    for step in steps:
        cmd = [sys.executable, os.path.join(HERE, step[0])] + (flag if step[0] != "run_rounding_comparison.py" else [])
        t0 = time.time()
        print(f"=== {step[0]} ===", flush=True)
        r = subprocess.run(cmd, cwd=HERE)
        print(f"    ({time.time()-t0:.1f}s, exit {r.returncode})", flush=True)
        if r.returncode != 0:
            print(f"STEP FAILED: {step[0]}", flush=True)
            sys.exit(1)
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()
