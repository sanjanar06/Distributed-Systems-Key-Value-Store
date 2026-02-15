import subprocess
import time
import signal

SERVER_ADDR = "127.0.0.1:3777"
SERVER_CMD = ["just", "p1::service", "0.0.0.0:3777"]

def run_with_server(cmd):
    print(f"Starting server: {' '.join(SERVER_CMD)}")
    server_proc = subprocess.Popen(SERVER_CMD)
    time.sleep(2)  # Wait for server to start
    try:
        print(f"Running: {cmd}")
        subprocess.run(cmd, shell=True, check=True)
    finally:
        print("Killing server...")
        server_proc.terminate()
        try:
            server_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server_proc.kill()
        time.sleep(1)

# Testcases
for i in range(1, 6):
    run_with_server(f"just p1::testcase {i} {SERVER_ADDR}")

# Fuzz tests
run_with_server(f"just p1::fuzz 1 no {SERVER_ADDR}")
run_with_server(f"just p1::fuzz 3 no {SERVER_ADDR}")
run_with_server(f"just p1::fuzz 3 yes {SERVER_ADDR}")

# YCSB benchmarks
for wload in ['a', 'b', 'c', 'd', 'e', 'f']:
    run_with_server(f"just p1::bench 1 {wload} {SERVER_ADDR}")

for wload in ['a', 'c', 'e']:
    for nclis in [10, 25, 40, 55, 70, 85]:
        run_with_server(f"just p1::bench {nclis} {wload} {SERVER_ADDR}")