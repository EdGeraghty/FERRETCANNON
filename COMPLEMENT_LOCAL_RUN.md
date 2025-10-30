# Running Complement locally (build + single-test pattern)

This file documents the local workflow I use to build the Complement image and run a focused complement test on Windows using WSL or PowerShell.

Use this when you want to run a single Complement test locally without remote runners.

Steps
1. Build the Complement Docker image (from repository root). This uses the included `Complement.Dockerfile` and tags the image `complement-ferretcannon:latest`:

```powershell
docker build -f Complement.Dockerfile -t complement-ferretcannon:latest .
```

2. Run the focused Go test from WSL (recommended) so Complement runs inside WSL's Go toolchain but uses the Docker image you just built.
   - This example runs `TestIsDirectFlagLocal` only and saves the output to `complement-test-output-7.txt` in the repo root.
   - Update paths or test name as required.

```powershell
wsl bash -lc 'cd /mnt/c/Users/Ed/FERRETCANNON/complement-src; \
export COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest; \
export COMPLEMENT_SPAWN_HS_TIMEOUT_SECS=180; \
export COMPLEMENT_HOSTNAME_RUNNING_COMPLEMENT=host.docker.internal; \
go test -v -run TestIsDirectFlagLocal ./tests/ 2>&1 | tee /mnt/c/Users/Ed/FERRETCANNON/complement-test-output-7.txt'
```

Notes and troubleshooting
- Ensure Docker Desktop / Docker Engine is running on Windows before building the image.
- The WSL command above mounts Windows paths under `/mnt/c/...` — adjust the path if your user or repo location differs.
- If you prefer to run entirely inside WSL: open your WSL distro, cd into the repository path, build the image and run the `go test` command directly.
- If Go or Docker are not available in WSL, using `wsl` from PowerShell will still allow the host Docker engine to be used (Docker Desktop publishes the daemon to WSL), but confirm your WSL environment can run `go test`.
- The `COMPLEMENT_` environment variables configure Complement for the run; tweak the timeout and hostname variables as needed for your environment.
- Output is written to `complement-test-output-7.txt` for later inspection.

Recommended sequence (copy-paste)

```powershell
docker build -f Complement.Dockerfile -t complement-ferretcannon:latest .

# then run the focused test (single-line):
wsl bash -lc 'cd /mnt/c/Users/Ed/FERRETCANNON/complement-src; export COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest; export COMPLEMENT_SPAWN_HS_TIMEOUT_SECS=180; export COMPLEMENT_HOSTNAME_RUNNING_COMPLEMENT=host.docker.internal; go test -v -run TestGetMissingEventsGapFilling ./tests/ 2>&1 | tee /mnt/c/Users/Ed/FERRETCANNON/complement-TestGetMissingEventsGapFilling.txt'
```

If you want me to push this change and open the PR for `inbound-federation-fixes`, say `push and open PR` and confirm GitHub CLI is available or provide the path.
