#!/bin/bash
# run-complement.sh - Helper script to run Complement tests against FERRETCANNON
#
# This script automates the process of building the Complement Docker image
# and running the test suite.
#
# Big shoutout to the FERRETCANNON massive for spec compliance! 🎆

set -e

# Colours for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Colour

# Default values
IMAGE_NAME="complement-ferretcannon"
COMPLEMENT_DIR="./complement-checkout"
TEST_PATTERN=""
TIMEOUT="30m"
BUILD_IMAGE=true
CLONE_COMPLEMENT=true

# Print usage
usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -h, --help              Show this help message"
    echo "  -t, --test PATTERN      Run specific test pattern (e.g., TestRegistration)"
    echo "  -T, --timeout DURATION  Set test timeout (default: 30m)"
    echo "  -n, --no-build          Skip building the Docker image"
    echo "  -N, --no-clone          Skip cloning Complement (use existing checkout)"
    echo "  -c, --complement DIR    Use specific Complement directory (default: ./complement-checkout)"
    echo ""
    echo "Examples:"
    echo "  $0                                      # Run all tests"
    echo "  $0 -t TestRegistration                 # Run registration tests only"
    echo "  $0 -T 1h -t TestFederation             # Run federation tests with 1 hour timeout"
    echo "  $0 -n -N                                # Skip build and clone, just run tests"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            usage
            exit 0
            ;;
        -t|--test)
            TEST_PATTERN="$2"
            shift 2
            ;;
        -T|--timeout)
            TIMEOUT="$2"
            shift 2
            ;;
        -n|--no-build)
            BUILD_IMAGE=false
            shift
            ;;
        -N|--no-clone)
            CLONE_COMPLEMENT=false
            shift
            ;;
        -c|--complement)
            COMPLEMENT_DIR="$2"
            shift 2
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            usage
            exit 1
            ;;
    esac
done

echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║${NC}  ${GREEN}FERRETCANNON Complement Test Suite${NC}                         ${BLUE}║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check prerequisites
echo -e "${BLUE}⚙️  Checking prerequisites...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Docker is not installed or not in PATH${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Docker found${NC}"

if ! command -v go &> /dev/null; then
    echo -e "${RED}❌ Go is not installed or not in PATH${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Go found ($(go version))${NC}"

# Build Docker image
if [ "$BUILD_IMAGE" = true ]; then
    echo ""
    echo -e "${BLUE}🔨 Building Complement Docker image...${NC}"
    docker build -t ${IMAGE_NAME}:latest -f Complement.Dockerfile . || {
        echo -e "${RED}❌ Failed to build Docker image${NC}"
        exit 1
    }
    echo -e "${GREEN}✅ Docker image built successfully${NC}"
else
    echo -e "${YELLOW}⏭️  Skipping Docker image build${NC}"
fi

# Clone or update Complement
if [ "$CLONE_COMPLEMENT" = true ]; then
    echo ""
    if [ -d "$COMPLEMENT_DIR" ]; then
        echo -e "${BLUE}📥 Updating Complement repository...${NC}"
        cd "$COMPLEMENT_DIR"
        git pull || {
            echo -e "${YELLOW}⚠️  Failed to update Complement, using existing version${NC}"
        }
        cd ..
    else
        echo -e "${BLUE}📥 Cloning Complement repository...${NC}"
        git clone https://github.com/matrix-org/complement.git "$COMPLEMENT_DIR" || {
            echo -e "${RED}❌ Failed to clone Complement${NC}"
            exit 1
        }
    fi
    echo -e "${GREEN}✅ Complement repository ready${NC}"
else
    echo -e "${YELLOW}⏭️  Skipping Complement clone${NC}"
    if [ ! -d "$COMPLEMENT_DIR" ]; then
        echo -e "${RED}❌ Complement directory not found: $COMPLEMENT_DIR${NC}"
        exit 1
    fi
fi

# Run tests
echo ""
echo -e "${BLUE}🧪 Running Complement tests...${NC}"
echo -e "${BLUE}   Image: ${IMAGE_NAME}:latest${NC}"
echo -e "${BLUE}   Timeout: ${TIMEOUT}${NC}"
if [ -n "$TEST_PATTERN" ]; then
    echo -e "${BLUE}   Pattern: ${TEST_PATTERN}${NC}"
else
    echo -e "${BLUE}   Running: All tests${NC}"
fi
echo ""

cd "$COMPLEMENT_DIR"

# Set up test command
TEST_CMD="go test -v -timeout ${TIMEOUT}"
if [ -n "$TEST_PATTERN" ]; then
    TEST_CMD="${TEST_CMD} -run ${TEST_PATTERN}"
fi
TEST_CMD="${TEST_CMD} ./tests/..."

# Export environment variable and run tests
export COMPLEMENT_BASE_IMAGE="${IMAGE_NAME}:latest"
eval $TEST_CMD

TEST_EXIT_CODE=$?

# Display results
echo ""
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║${NC}  ${GREEN}✅ All tests PASSED!${NC}                                         ${GREEN}║${NC}"
    echo -e "${GREEN}╚═══════════════════════════════════════════════════════════════╝${NC}"
else
    echo -e "${RED}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║${NC}  ${RED}❌ Some tests FAILED${NC}                                          ${RED}║${NC}"
    echo -e "${RED}╚═══════════════════════════════════════════════════════════════╝${NC}"
fi

echo ""
echo -e "${BLUE}Big shoutout to the FERRETCANNON massive! 🎆${NC}"

exit $TEST_EXIT_CODE
