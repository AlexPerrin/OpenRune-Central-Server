"""The Central track script: python3 -m unittest tools/test_track.py"""
import os, shutil, subprocess, tempfile, unittest
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
