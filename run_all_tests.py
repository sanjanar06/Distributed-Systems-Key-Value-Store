import subprocess
import os
import time

# --- CONFIGURATION ---
# Replace with the actual SSH address of your server node
NODE0_SSH = "sanji@clnode300.clemson.cloudlab.us"
SERVER_IP = "10.10.1.1" 
SERVER_ADDR = f"{SERVER_IP}:3777"
# Use the folder name directly since you are running from inside it
REPO_NAME = "Distributed-Systems-Key-Value-Store"
CLASSPATH_FILE = ".classpath"

def ensure_classpath():
    """Generates the .classpath file if missing."""
    if not os.path.exists(CLASSPATH_FILE):
        print("[INIT] Generating .classpath...")
        try:
            subprocess.run("cd kvstore && ./gradlew -q printClasspath > ../.classpath", shell=True, check=True)
        except subprocess.CalledProcessError:
            print("[ERROR] Failed to generate classpath.")
            exit(1)

def manage_remote_server():
    """Remotely kills any old server and starts a fresh one on Node 0."""
    print(f"[SERVER] Cleaning up Node 0...")
    # 1. Kill old server processes
    subprocess.run(f"ssh {NODE0_SSH} 'pkill -f KVServer || true'", shell=True, capture_output=True)
    
    print(f"[SERVER] Starting fresh server...")
    # 2. Start the server (using nohup so it lives after SSH disconnects)
    start_cmd = (
        f"ssh {NODE0_SSH} \"nohup bash -lc 'cd {REPO_NAME} && just p1::service 0.0.0.0:3777' "
        f"> server_log.out 2>&1 &\""
    )
    subprocess.run(start_cmd, shell=True)
    
    # 3. CRITICAL: Wait for the port to actually open
    print("[SERVER] Waiting for port 3777 to open...")
    max_retries = 15
    for i in range(max_retries):
        # Check if the port is listening on the remote node
        check_port = f"ssh {NODE0_SSH} 'nc -z localhost 3777' && echo 'OPEN' || echo 'CLOSED'"
        status = subprocess.check_output(check_port, shell=True, text=True).strip()
        
        if "OPEN" in status:
            print(f"[SERVER] Server is UP on try {i+1}!")
            return
        time.sleep(1) # Wait 1 second before retrying
    
    print("[ERROR] Server failed to start within 15 seconds.")
    exit(1)

def run_recipe(recipe_cmd):
    """Automates server restart on Node 0 then runs the client recipe on Node 1."""
    # Restart the server on Node 0 before every test
    manage_remote_server()
    
    print(f"[CLIENT] Running: {recipe_cmd}")
    
    # Local cleanup on Node 1
    subprocess.run(["just", "p1::kill"], capture_output=True)
    
    # Execute with high-concurrency limits
    full_cmd = f"ulimit -n 4096 && {recipe_cmd} {SERVER_ADDR}"
    
    try:
        result = subprocess.run(["bash", "-c", full_cmd], text=True)
        if result.returncode != 0:
            print(f"[FAILED] {recipe_cmd} exited with code {result.returncode}")
    except Exception as e:
        print(f"[ERROR] {e}")

# --- EXECUTION ---

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

print("\n[COMPLETE] All automated tests finished.")