import subprocess
import os

# --- CONFIGURATION ---
SERVER_ADDR = "10.10.1.1:3777"
CLASSPATH_FILE = ".classpath"

def ensure_classpath():
    """Checks if the .classpath file exists; if not, generates it."""
    if not os.path.exists(CLASSPATH_FILE):
        print(f"[INIT] {CLASSPATH_FILE} not found. Generating now...")
        try:
            # Navigate to kvstore to run gradlew, then save output to root
            cmd = "cd kvstore && ./gradlew -q printClasspath > ../.classpath"
            subprocess.run(cmd, shell=True, check=True)
            print("[SUCCESS] .classpath generated.")
        except subprocess.CalledProcessError as e:
            print(f"[ERROR] Failed to generate classpath: {e}")
            exit(1)
    else:
        print(f"[INIT] Using existing {CLASSPATH_FILE}")

def run_recipe(recipe_cmd):
    """Executes a just recipe locally with optimized concurrency settings."""
    print(f"\n[RUNNING] {recipe_cmd}...")
    
    # 1. Local cleanup
    subprocess.run(["just", "p1::kill"], capture_output=True)
    
    # 2. Execute with high-concurrency limits
    full_cmd = f"ulimit -n 4096 && {recipe_cmd} {SERVER_ADDR}"
    
    try:
        result = subprocess.run(["bash", "-c", full_cmd], text=True)
        if result.returncode != 0:
            print(f"[FAILED] {recipe_cmd} exited with code {result.returncode}")
    except Exception as e:
        print(f"[ERROR] {e}")

# --- EXECUTION ---

# Ensure environment is ready before starting tests
ensure_classpath()

# 1. Standard Testcases
for i in range(1, 6):
    run_recipe(f"just p1::testcase {i}")

# 2. Fuzzing Scenarios
run_recipe("just p1::fuzz 1 no")
run_recipe("just p1::fuzz 3 no")
run_recipe("just p1::fuzz 3 yes")

# 3. Baseline YCSB (1 Client)
for wload in ['a', 'b', 'c', 'd', 'e', 'f']:
    run_recipe(f"just p1::bench 1 {wload}")

# 4. Scalability Benchmarks
for wload in ['a', 'c', 'e']:
    for nclis in [10, 25, 40, 55, 70, 85]:
        run_recipe(f"just p1::bench {nclis} {wload}")

print("\n[COMPLETE] All client-side tests finished.")