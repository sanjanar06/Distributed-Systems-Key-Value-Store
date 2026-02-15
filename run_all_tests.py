
import subprocess
import time

# SSH details for node 0 (server) and node 1 (client)
NODE0 = "user@node0"  # Replace with your actual username and hostname/IP for node 0
NODE1 = "user@node1"  # Replace with your actual username and hostname/IP for node 1

SERVER_ADDR = "node0:3777"  # Use node0's hostname or IP
SERVER_CMD = "just p1::service 0.0.0.0:3777"

def run_with_server(client_cmd):
    print(f"Starting server on {NODE0}: {SERVER_CMD}")
    # Start server on node 0 via SSH (in background)
    server_ssh_cmd = [
        "ssh", NODE0, f"nohup {SERVER_CMD} > server.log 2>&1 & echo $!"
    ]
    server_proc = subprocess.run(server_ssh_cmd, capture_output=True, text=True)
    server_pid = server_proc.stdout.strip()
    print(f"Server PID on node0: {server_pid}")
    time.sleep(2)  # Wait for server to start

    try:
        print(f"Running client on {NODE1}: {client_cmd}")
        client_ssh_cmd = [
            "ssh", NODE1, client_cmd
        ]
        subprocess.run(client_ssh_cmd, check=True)
    finally:
        print("Killing server on node0...")
        kill_cmd = f"kill {server_pid}"
        subprocess.run(["ssh", NODE0, kill_cmd])
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