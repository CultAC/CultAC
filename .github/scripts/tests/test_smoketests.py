"""Local orchestration/security checks; no Minecraft downloads or Docker needed."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parents[1]
spec = importlib.util.spec_from_file_location('collector', SCRIPTS / 'collect-smoketest-artifacts.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
spec = importlib.util.spec_from_file_location('egress', SCRIPTS / 'egress-proxy.py')
egress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(egress)


class SmoketestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def executable(self, path, source):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        path.chmod(0o755)

    def test_artifacts_cannot_follow_links_into_host_or_private_fixtures(self):
        sandbox = self.root / 'sandbox'
        logs = sandbox / 'workspace/server/logs'
        logs.mkdir(parents=True)
        secret = self.root / 'host-secret'
        secret.write_text('must not be copied')
        (logs / 'latest.log').write_text('private logs must not be copied')
        (sandbox / 'workspace/artifacts').mkdir(parents=True)
        (sandbox / 'workspace/artifacts/suite.json').write_text('evidence')
        (logs / 'secret.log').symlink_to(secret)
        (logs / 'host-dir').symlink_to(self.root, target_is_directory=True)
        os.mkfifo(logs / 'pipe')
        (sandbox / 'workspace/artifacts/private.txt').write_text('private')
        (sandbox / 'workspace/artifacts/symlink.txt').symlink_to(secret)
        destination = self.root / 'evidence'
        collector.collect(sandbox, destination, 'bedrock')
        files = [p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()]
        self.assertEqual(files, ['artifacts/suite.json'])
        self.assertEqual((destination / files[0]).read_text(), 'evidence')

    def test_artifacts_reject_linked_ancestors(self):
        sandbox = self.root / 'sandbox'
        (sandbox / 'workspace').mkdir(parents=True)
        (self.root / 'host/logs').mkdir(parents=True)
        (self.root / 'host/logs/private.log').write_text('secret')
        (self.root / 'host/suite.json').write_text('secret')
        (sandbox / 'workspace/artifacts').symlink_to(self.root / 'host', target_is_directory=True)
        collector.collect(sandbox, self.root / 'evidence', 'bedrock')
        self.assertEqual(list((self.root / 'evidence').rglob('*')), [])

    def test_sandbox_uses_copies_and_never_forwards_credentials(self):
        workspace, home, runner = [self.root / name for name in ('workspace', 'home', 'runner')]
        runner.mkdir()
        (workspace / 'ci/.github').mkdir(parents=True)
        shutil.copytree(SCRIPTS, workspace / 'ci/.github/scripts')
        (workspace / 'ci/scripts/smoketests').mkdir(parents=True)
        (workspace / 'ci/scripts/smoketests/bedrock-replay-runtime.json').write_text('{}')
        for name in ('cultac', 'mcp-client-smoketest'):
            (workspace / name).mkdir()
            (workspace / name / 'input').write_text('trusted original')
        (home / '.gradle/caches').mkdir(parents=True)
        (home / '.gradle/caches/input').write_text('trusted cache')
        args_file, output = self.root / 'docker-args.json', self.root / 'outputs'
        fake_bin = self.root / 'bin'
        self.executable(fake_bin / 'docker', '''#!/usr/bin/env python3
import json, pathlib, sys
args = sys.argv[1:]
pathlib.Path('docker-args.json').write_text(json.dumps(args))
for index, arg in enumerate(args):
    if arg == '--mount':
        mount = dict(part.split('=', 1) for part in args[index + 1].split(','))
        source = pathlib.Path(mount['src'])
        target = source / ('.gradle/caches/input' if source.name == 'home' else 'cultac/input')
        target.write_text('malicious mutation in disposable copy')
print('::set-env name=EVIL::malicious output')
''')
        env = dict(os.environ, PATH=f'{fake_bin}:{os.environ["PATH"]}', HOME=str(home),
                   GITHUB_WORKSPACE=str(workspace), RUNNER_TEMP=str(runner), GITHUB_OUTPUT=str(output),
                   SMOKETEST_REPO_TOKEN='synthetic-secret', ACTIONS_RUNTIME_TOKEN='synthetic-runtime-token')
        result = subprocess.run(['bash', str(SCRIPTS / 'run-smoketest-container.sh'), 'java'],
                                env=env, cwd=self.root, capture_output=True, text=True, check=True)
        args = json.loads(args_file.read_text())
        mounts = [args[i + 1] for i, value in enumerate(args) if value == '--mount']
        sandbox = Path(output.read_text().strip().removeprefix('path='))
        self.assertEqual(len(mounts), 2)
        self.assertTrue(all(f'src={sandbox}/' in mount for mount in mounts))
        self.assertEqual((home / '.gradle/caches/input').read_text(), 'trusted cache')
        self.assertEqual((workspace / 'cultac/input').read_text(), 'trusted original')
        self.assertNotEqual((sandbox / 'home/.gradle/caches/input').stat().st_ino,
                            (home / '.gradle/caches/input').stat().st_ino)
        for forbidden in ('synthetic-secret', 'synthetic-runtime-token', 'docker.sock', '--privileged',
                          '--env-file', '--network=host', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN'):
            self.assertNotIn(forbidden, '\n'.join(args))
        self.assertIn('--read-only', args)
        self.assertIn('--network=none', args)
        self.assertIn('/tmp:rw,exec,nosuid,size=1g', args)
        self.assertIn('--cap-drop=ALL', args)
        self.assertIn('--security-opt=no-new-privileges:true', args)
        lines = result.stdout.splitlines()
        marker = lines[0].removeprefix('::stop-commands::')
        self.assertEqual(len(marker), 64)
        self.assertEqual(lines[-1], f'::{marker}::')

    def test_bedrock_sandbox_leaves_only_through_the_egress_proxy(self):
        workspace, home, runner = [self.root / name for name in ('workspace', 'home', 'runner')]
        runner.mkdir()
        (workspace / 'ci/.github').mkdir(parents=True)
        shutil.copytree(SCRIPTS, workspace / 'ci/.github/scripts')
        (workspace / 'ci/scripts/smoketests').mkdir(parents=True)
        (workspace / 'ci/scripts/smoketests/bedrock-replay-runtime.json').write_text('{}')
        for name in ('cultac', 'replay', 'scenarios'):
            (workspace / name).mkdir()
        (home / '.gradle').mkdir(parents=True)
        calls, output = self.root / 'docker-calls.jsonl', self.root / 'outputs'
        fake_bin = self.root / 'bin'
        self.executable(fake_bin / 'docker', f'''#!/usr/bin/env python3
import json, sys
with open({str(calls)!r}, 'a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
''')
        env = dict(os.environ, PATH=f'{fake_bin}:{os.environ["PATH"]}', HOME=str(home),
                   GITHUB_WORKSPACE=str(workspace), RUNNER_TEMP=str(runner), GITHUB_OUTPUT=str(output))
        subprocess.run(['bash', str(SCRIPTS / 'run-smoketest-container.sh'), 'bedrock'],
                       env=env, cwd=self.root, capture_output=True, text=True, check=True)
        invocations = [json.loads(line) for line in calls.read_text().splitlines()]
        network = next(args for args in invocations if args[:2] == ['network', 'create'])
        self.assertEqual(network[2], '--internal')
        name = network[3]
        proxy = next(args for args in invocations if args[0] == 'run' and '-d' in args)
        sandbox = next(args for args in invocations if args[0] == 'run' and '-d' not in args)
        for args in (proxy, sandbox):
            self.assertIn('--read-only', args)
            self.assertIn('--cap-drop=ALL', args)
            self.assertIn('--security-opt=no-new-privileges:true', args)
            self.assertNotIn('--network=host', args)
            self.assertNotIn('--privileged', args)
        self.assertEqual(proxy[proxy.index('--name') + 1], name)
        self.assertEqual(proxy[-5:], ['cultac-smoketest', 'python3', '-I', '/egress-proxy.py', '3128'])
        self.assertIn(['network', 'connect', name, name], invocations)
        self.assertEqual(sandbox[sandbox.index('--network') + 1], name)
        self.assertIn(f'CULTAC_EGRESS_PROXY={name}:3128', sandbox)
        self.assertIn(['rm', '-f', name], invocations)
        self.assertIn(['network', 'rm', name], invocations)

    def test_egress_proxy_allows_only_https_to_the_runtime_services(self):
        for host in ('client.discovery.minecraft-services.net', 'authorization.franchise.minecraft-services.net',
                     'piston-data.mojang.com', 'MOJANG.COM.'):
            self.assertTrue(egress.allowed(host, '443'), host)
        for host, port in (('piston-data.mojang.com', '80'), ('evilmojang.com', '443'),
                           ('mojang.com.example.org', '443'), ('github.com', '443'), ('127.0.0.1', '443')):
            self.assertFalse(egress.allowed(host, port), host)

    def test_only_reach_and_dedup_run_and_failure_does_not_skip_reach(self):
        (self.root / 'cultac').mkdir()
        (self.root / 'mcp-client-smoketest').mkdir()
        self.executable(self.root / 'cultac/gradlew', '''#!/usr/bin/env bash
mkdir -p cultac/build/libs
touch cultac/build/libs/CultAC-dev.jar
''')
        self.executable(self.root / 'mcp-client-smoketest/gradlew', '#!/usr/bin/env bash\nexit 0\n')
        fake_bin = self.root / 'bin'
        self.executable(fake_bin / 'xvfb-run', '#!/usr/bin/env bash\nshift 3\nexec "$@"\n')
        self.executable(fake_bin / 'java', '''#!/usr/bin/env python3
import pathlib, sys
name = next(arg for arg in sys.argv if arg.startswith('ac.grim.'))
with pathlib.Path('sections.txt').open('a') as stream:
    stream.write(name + '\\n')
sys.exit(3 if 'Deduplication' in name else 0)
''')
        env = dict(os.environ, PATH=f'{fake_bin}:{os.environ["PATH"]}')
        result = subprocess.run(['bash', str(SCRIPTS / 'run-selected-smoketests.sh'), 'java'],
                                env=env, cwd=self.root, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual((self.root / 'mcp-client-smoketest/sections.txt').read_text().splitlines(), [
            'ac.grim.movementvalidation.DeduplicationVerificationMain',
            'ac.grim.movementvalidation.RealServerCombatReachValidationMain',
        ])
        rows = (self.root / 'mcp-client-smoketest/.real-validation/runs/github-ci/section-times.tsv').read_text().splitlines()
        self.assertTrue(rows[1].endswith('\t3'))
        self.assertTrue(rows[2].endswith('\t0'))

    def test_bedrock_preserves_the_replay_suite_and_trusted_runtime_lock(self):
        (self.root / 'cultac').mkdir()
        self.executable(self.root / 'cultac/gradlew', '''#!/usr/bin/env bash
mkdir -p cultac/bukkit/build/libs
touch cultac/bukkit/build/libs/CultAC-paper.jar
''')
        self.executable(self.root / 'replay/scripts/run-movement-regressions.py', '''#!/usr/bin/env python3
import json, os, pathlib, sys
pathlib.Path('replay-args.json').write_text(json.dumps(sys.argv[1:]))
pathlib.Path('replay-java-options.txt').write_text(os.environ.get('JAVA_TOOL_OPTIONS', ''))
''')
        fake_bin = self.root / 'bin'
        self.executable(fake_bin / 'git', '#!/usr/bin/env bash\nprintf "%040d\\n" 1\n')
        env = dict(os.environ, PATH=f'{fake_bin}:{os.environ["PATH"]}')
        subprocess.run(['bash', str(SCRIPTS / 'run-selected-smoketests.sh'), 'bedrock'],
                       env=env, cwd=self.root, capture_output=True, text=True, check=True)
        args = json.loads((self.root / 'replay-args.json').read_text())
        self.assertEqual(args, [
            '--scenarios', 'scenarios/scenarios',
            '--cultac-jar', 'cultac/bukkit/build/libs/CultAC-paper.jar',
            '--server-dir', 'server', '--artifacts', 'artifacts', '--seed', '42',
            '--replay-runtime-lock', 'replay/.ci-runtime-offline.json',
        ])
        self.assertEqual((self.root / 'replay-java-options.txt').read_text(), '')
        env.update(CULTAC_EGRESS_PROXY='cultac-egress-test:3128', JAVA_TOOL_OPTIONS='-Duser.home=/home/runner')
        subprocess.run(['bash', str(SCRIPTS / 'run-selected-smoketests.sh'), 'bedrock'],
                       env=env, cwd=self.root, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads((self.root / 'replay-args.json').read_text()), args)
        self.assertEqual((self.root / 'replay-java-options.txt').read_text(),
                         '-Duser.home=/home/runner -Dhttp.proxyHost=cultac-egress-test -Dhttp.proxyPort=3128'
                         ' -Dhttps.proxyHost=cultac-egress-test -Dhttps.proxyPort=3128')

    def test_workflow_trust_order_and_branch_filters(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/smoketests.yml').read_text())
        events = workflow.get('on', workflow.get(True))
        for event in ('push', 'pull_request'):
            self.assertEqual(events[event]['branches'], ['main', 'experimental', 'dev'])
        self.assertNotIn('pull_request_target', events)
        self.assertNotIn('edited', events['pull_request']['types'])
        self.assertEqual(workflow['permissions'], {'contents': 'read'})
        jobs = workflow['jobs']
        self.assertEqual(jobs['smoketest']['strategy']['matrix']['suite'], ['java', 'bedrock'])
        self.assertIn("github.event_name != 'pull_request'", jobs['smoketest']['if'])
        self.assertEqual(jobs['pr-build']['if'], "github.event_name == 'pull_request'")
        pr_steps = jobs['pr-build']['steps']
        self.assertFalse(any('secrets.' in str(s) or 'actions/cache' in str(s) for s in pr_steps))
        self.assertFalse(any('upload-artifact' in str(s) for s in pr_steps))
        self.assertTrue(any(s.get('working-directory') == 'ci' and 'gradlew' in str(s.get('run', '')) for s in pr_steps))
        for step in pr_steps:
            if step.get('uses', '').startswith('actions/checkout@'):
                self.assertFalse(step['with']['persist-credentials'])
        steps = jobs['smoketest']['steps']
        save = next(i for i, s in enumerate(steps) if s.get('uses', '').startswith('actions/cache/save@'))
        checkout = next(i for i, s in enumerate(steps) if s.get('with', {}).get('path') == 'cultac')
        execute = next(i for i, s in enumerate(steps) if s.get('id') == 'sandbox')
        self.assertLess(checkout, save)
        self.assertLess(save, execute)
        self.assertIn('trusted-smoketest-v2', str(steps))
        self.assertFalse(any('allow-unsafe-pr-checkout' in str(s) for s in steps))
        for step in steps:
            self.assertNotEqual(step.get('uses', ''), 'actions/cache@v4')
            self.assertNotIn('gradle/actions/setup-gradle', step.get('uses', ''))
            if step.get('uses', '').startswith('actions/checkout@'):
                self.assertFalse(step['with']['persist-credentials'])

    def test_artifact_size_and_file_count_limits(self):
        sandbox = self.root / 'sandbox'
        logs = sandbox / 'workspace/server/logs'
        logs.mkdir(parents=True)
        (sandbox / 'workspace/artifacts').mkdir(parents=True)
        (sandbox / 'workspace/artifacts/suite.json').write_text('ok')
        (sandbox / 'workspace/artifacts/junit.xml').write_bytes(b'x' * (collector.MAX_FILE_BYTES + 1))
        destination = self.root / 'evidence'
        collector.collect(sandbox, destination, 'bedrock')
        self.assertEqual((destination / 'artifacts/suite.json').read_text(), 'ok')
        self.assertFalse((destination / 'artifacts/junit.xml').exists())

    def test_pr_wrapper_has_no_private_inputs(self):
        content = (SCRIPTS / 'run-untrusted-pr-build.sh').read_text()
        self.assertIn('GITHUB_WORKSPACE/cultac', content)
        self.assertNotIn('mcp-client-smoketest', content)
        self.assertNotIn('replay/.', content)
        self.assertNotIn('--env-file', content)
        self.assertIn('--network=none', content)
        self.assertIn('--offline', content)
        self.assertNotIn('ACTIONS_RUNTIME_TOKEN', content)
        self.assertNotIn('docker.sock', content.replace('Docker socket', ''))

    def test_runtime_prefetch_requires_hashes_and_pinned_urls(self):
        content = (SCRIPTS / 'prefetch-bedrock-runtime.py').read_text()
        self.assertIn('sha256', content)
        self.assertIn('https://', content)
        self.assertIn('.ci-runtime-offline.json', content)


if __name__ == '__main__':
    unittest.main()
