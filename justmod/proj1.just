set working-directory := '..'

# list project 1 just recipes
default:
    @just --list p1 --unsorted

# install extra dependencies of your KV code
deps:
    # Install Java if not present
    if ! command -v java >/dev/null 2>&1; then
        echo "Java not found. Installing OpenJDK..."
        sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
    else
        echo "Java is already installed."
    fi
    # Set JAVA_HOME if not set
    if [ -z "$JAVA_HOME" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
        echo 'export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"' >> ~/.bashrc
        echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
        source ~/.bashrc
    fi

# build your executables in release mode
build:
    cd kvstore && chmod +x gradlew && ./gradlew build

# clean the build of your executables
clean:
    cd kvstore && ./gradlew clean

# run your KV store server
server listen="0.0.0.0:3777":
    cd kvstore && ./gradlew runServer --args="{{listen}}" --console=plain -q

# run you KV store client in stdin/out interface mode
client server="127.0.0.1:3777":
    java -Xmx128m -cp "$(cat .classpath)" KVClient {{server}}

# Test 1(single client): PUT, GET, SWAP, DELETE, SCAN on sorted keys, overwrite, missing-key edges
test1 server="127.0.0.1:3777":
    printf 'PUT apple 1\nPUT banana 2\nPUT cherry 3\nPUT date 4\nGET apple\nGET banana\nGET missing\nSWAP banana 20\nGET banana\nSWAP ghost val\nGET ghost\nDELETE cherry\nDELETE cherry\nGET cherry\nPUT apple 100\nGET apple\nSCAN apple date\nSCAN banana ghost\nSCAN z zz\nSTOP\n' \
        | just p1::client "{{server}}"

# Test 2(single client): lexicographic ordering in SCAN, single-key SCAN, overwrite chain, empty ranges
test2 server="127.0.0.1:3777":
    printf 'PUT k01 v1\nPUT k02 v2\nPUT k03 v3\nPUT k04 v4\nPUT k05 v5\nPUT k03 UPDATED\nSCAN k01 k05\nSCAN k03 k03\nSCAN k06 k09\nDELETE k02\nDELETE k04\nSCAN k01 k05\nGET k02\nGET k04\nGET k03\nSTOP\n' \
        | just p1::client "{{server}}"

# Test 3(concurrent, no conflicts): 2 clients on disjoint key spaces
test3 server="127.0.0.1:3777":
    printf 'PUT a_1 alpha\nPUT a_2 beta\nGET a_1\nGET a_2\nSWAP a_1 ALPHA\nGET a_1\nSCAN a_1 a_2\nDELETE a_2\nGET a_2\nSTOP\n' \
        | just p1::client "{{server}}" &
    printf 'PUT b_1 one\nPUT b_2 two\nPUT b_3 three\nGET b_1\nGET b_3\nDELETE b_2\nGET b_2\nSCAN b_1 b_3\nSTOP\n' \
        | just p1::client "{{server}}" &

# Test 4(concurrent, conflicting): 2 clients racing on shared keys 
test4 server="127.0.0.1:3777":
    printf 'PUT shared x1\nGET shared\nSWAP shared x2\nGET shared\nPUT counter c1\nSWAP counter c2\nGET counter\nGET shared\nSTOP\n' \
        | just p1::client "{{server}}" &
    printf 'PUT shared y1\nGET shared\nSWAP shared y2\nGET shared\nPUT counter d1\nSWAP counter d2\nGET counter\nGET shared\nSTOP\n' \
        | just p1::client "{{server}}" &

# Test 5(concurrent, conflicting): 3 clients — write/delete/scan race 
test5 server="127.0.0.1:3777":
    printf 'PUT r_1 w1\nPUT r_2 w2\nPUT r_3 w3\nPUT r_4 w4\nPUT r_5 w5\nSTOP\n' \
        | just p1::client "{{server}}" &
    printf 'DELETE r_2\nDELETE r_4\nPUT r_3 OVERWRITE\nSWAP r_1 SWAPPED\nSTOP\n' \
        | just p1::client "{{server}}" &
    printf 'SCAN r_1 r_5\nGET r_1\nGET r_2\nGET r_3\nGET r_4\nGET r_5\nSTOP\n' \
        | just p1::client "{{server}}" &

# kill all processes of your KV store system
kill:
    -pkill -f 'KVServer' 2>/dev/null || true
    -pkill -f 'KVClient' 2>/dev/null || true
    -pkill -f 'gradle.*runServer' 2>/dev/null || true
    -pkill -f 'gradle.*runClient' 2>/dev/null || true

# NOTE: feel free to add more recipes as you see fit...
#       also feel free to add extra parameters to the recipes as you see fit,
#       but don't change the existing parameters

python_run := if `which uv || true` != "" { "uv run" } else { "python3" }
tmpdir_prefix := "/tmp/madkv-p1"

# launch a long-running KV store service
service listen="0.0.0.0:3777":
    just p1::build
    just utils::build
    cargo run -p runner -r --bin service -- \
        --server-just-args p1::server "{{listen}}" \
        --manager-just-args none

# ensure a subdir under 'tmp/' exists
tmpdir subdir:
    mkdir -p "{{tmpdir_prefix}}/{{subdir}}"

# run a student-provided testcase
testcase num server="127.0.0.1:3777": (tmpdir "tests")
    just p1::build
    just "p1::test{{num}}" "{{server}}" \
        | tee "{{tmpdir_prefix}}/tests/test{{num}}.log"
    just p1::kill

# run a fuzz testing scenario
fuzz nclis conflict="no" server="127.0.0.1:3777": (tmpdir "fuzz")
    just p1::build
    just utils::build
    cargo run -p runner -r --bin fuzzer -- \
        --num-clis "{{nclis}}" \
        {{ if conflict == "no" { "" } else { "--conflict" } }} \
        --client-just-args p1::client "{{server}}" \
        | tee "{{tmpdir_prefix}}/fuzz/fuzz-{{nclis}}-{{conflict}}.log"
    just p1::kill

# run a YCSB benchmark workload
bench nclis wload server="127.0.0.1:3777": (tmpdir "bench")
    just p1::build
    just utils::build
    just utils::ycsb
    cargo run -p runner -r --bin bencher -- \
        --num-clis "{{nclis}}" \
        --workload "{{wload}}" \
        --client-just-args p1::client "{{server}}" \
        | tee "/tmp/madkv-p1/bench/bench-{{nclis}}-{{wload}}.log"
    just p1::kill

# generate .md report template from existing results (wip)
report:
    {{python_run}} sumgen/proj1.py
