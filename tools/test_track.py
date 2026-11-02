"""The Central track script: python3 -m unittest tools/test_track.py"""
import json, os, shutil, subprocess, tempfile, unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent


class Copy(unittest.TestCase):
    def test_a_run_reads_its_own_copy_of_the_tools(s):  # a tools ship moves the checkout: a running script keeps the copy it started from
        root = Path(tempfile.mkdtemp()); s.addCleanup(shutil.rmtree, root); (root / "harness/changes").mkdir(parents=True); (root / "harness/changes/x.md").write_text("")
        t = root / "checkout/tools"; shutil.copytree(TOOLS, t, ignore=shutil.ignore_patterns("__pycache__")); copies = root / "state/tools"
        run = lambda: subprocess.run(["bash", str(t / "track.sh"), "log", "x", "."], env={**os.environ, "HD_ROOT": str(root)}, capture_output=True, text=True)
        run(); run(); s.assertEqual(len(list(copies.iterdir())), 1)                     # one copy per content
        g = next(copies.iterdir()) / "tools/gate/gate.sh"; before = g.read_text()
        (t / "gate/gate.sh").write_text(before + "# shipped\n"); run()
        s.assertEqual((len(list(copies.iterdir())), g.read_text()), (2, before))       # the ship made a new copy, the first unchanged


class HostFault(unittest.TestCase):
    def test_a_host_fault_records_no_result_and_a_tree_failure_records_its_fail(s):  # osrs-harness sweep-18 N7: docker's 'cannot allocate memory' was the head's build FAIL
        root = Path(tempfile.mkdtemp()); s.addCleanup(shutil.rmtree, root); (root / "harness/changes").mkdir(parents=True); (root / "harness/changes/x.md").write_text("")
        (root / "hd.py").write_text("import os, sys\nassert sys.argv[1] == 'room'\nos.execvp(sys.argv[2], sys.argv[2:])\n")   # hd room: the host has room
        repo = root / "repo"; repo.mkdir(); g = lambda *a: subprocess.run(["git", "-C", str(repo), *a], capture_output=True, text=True).stdout.strip()
        g("init", "-q"); (repo / "f").write_text(""); g("add", "-A"); g("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "t"); tc = g("rev-parse", "HEAD")
        (root / "bin").mkdir(); docker = root / "bin/docker"
        build = lambda: subprocess.run(["bash", str(TOOLS / "build/build.sh"), "build", "x", tc], capture_output=True, text=True,
                                       env={**os.environ, "HD_ROOT": str(root), "CLONE": str(repo), "PATH": f"{root / 'bin'}:{os.environ['PATH']}"})
        rec = root / "state/x/build.json"
        docker.write_text('#!/bin/sh\ncase "$*" in *"--name build-x"*) echo "e: Unresolved reference: foo"; echo "BUILD FAILED"; exit 1;; esac\n'); docker.chmod(0o755)
        r = build(); s.assertEqual((r.returncode, json.loads(rec.read_text())["ok"]), (1, False), r.stdout)   # the tree's compile failure: ok:false
        rec.unlink()
        for fault in ("docker: Error response from daemon: cannot allocate memory.", "no space left on device"):
            docker.write_text(f'#!/bin/sh\ncase "$*" in *"--name build-x"*) echo "{fault}" >&2; exit 125;; esac\n')
            r = build(); s.assertEqual(r.returncode, 75, r.stdout + r.stderr); s.assertFalse(rec.exists())         # the host's: no result, the pass runs it again
        s.assertIn("docker run --init", (TOOLS / "build/build.sh").read_text())   # a stop's SIGTERM, which docker run proxies, ends the container with its script
