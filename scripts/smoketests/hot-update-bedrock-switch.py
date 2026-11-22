#!/usr/bin/env python3
"""Replay immutable Bedrock tapes over one physical session across engine and backend switches.

Uses the existing replay client, movement assertions and passive observer adapters.
Starts two isolated Paper backends and Geyser/Cult on Velocity; never edits the corpus.
"""
import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import time
import re
import inspect
import textwrap
import tempfile
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def compile_probe(args, velocity, destination):
    cli = args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-replay-cli.jar'
    observer = args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-velocity.jar'
    classes = destination / 'probe-classes'
    classes.mkdir()
    sources = sorted((ROOT / 'validation/velocity/bedrock-handoff').glob('*.java'))
    paper_api = args.paper_api
    if paper_api is None:
        candidates = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api').glob('26.3.build.*/*/*.jar'))
        paper_api = next((p for p in reversed(candidates) if not p.name.endswith('-sources.jar')), None)
    if paper_api is None or not paper_api.is_file():
        raise ValueError('Supply --paper-api with a cached Paper 26.3 API jar for the fixture helper')
    subprocess.run(['javac', '-proc:none', '-cp', str(cli) + ':' + str(velocity) + ':' + str(paper_api),
                    '-d', str(classes)] + [str(p) for p in sources], check=True)
    plugin = destination / 'HandoffObserver.jar'
    with ZipFile(plugin, 'w', ZIP_DEFLATED) as out, ZipFile(observer) as source:
        for item in source.infolist():
            if item.filename != 'velocity-plugin.json':
                out.writestr(item, source.read(item))
        for file in classes.rglob('HandoffReplayObserver*.class'):
            out.write(file, file.relative_to(classes).as_posix())
        out.writestr('velocity-plugin.json', json.dumps({
            'id': 'cult-bedrock-handoff-observer', 'name': 'Cult Bedrock Handoff Observer',
            'version': '1', 'main': 'ac.cultac.bedrockreplay.capture.HandoffReplayObserver',
            'dependencies': [{'id': 'cultac', 'optional': False}, {'id': 'geyser', 'optional': False}],
        }))
    fixture = destination / 'HandoffReplayFixture.jar'
    with ZipFile(fixture, 'w', ZIP_DEFLATED) as out, ZipFile(args.replay_root / 'build/libs/bedrock-packet-replay-0.2.0-fixture.jar') as source:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                out.writestr(item, source.read(item))
        for file in classes.rglob('HandoffReplayFixture*.class'):
            out.write(file, file.relative_to(classes).as_posix())
        out.writestr('plugin.yml', "name: HandoffReplayFixture\nversion: 1\nmain: ac.cultac.bedrockreplay.capture.HandoffReplayFixture\napi-version: '1.21'\n")
    return cli, classes, plugin, fixture


def runtime_status(native, pattern):
    offset=len(native.log())
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        native.command('cult runtimes')
        time.sleep(.2)
        if re.search(pattern,native.log()[offset:]): return
    raise AssertionError('Runtime ownership did not reach '+pattern+'; see '+str(native.log_path))


def plain(text):
    return re.sub(r'\x1b\[[0-9;]*m|§.', '', text)


def net(native, command, pattern):
    """Runs `cult net <command>` on the proxy console and returns the first answer matching pattern."""
    offset=len(native.log())
    native.command('cult net '+command)
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        match=re.search(pattern,plain(native.log()[offset:]))
        if match: return match
        time.sleep(.2)
    raise AssertionError('cult net '+command+' gave no answer matching '+pattern+'; see '+str(native.log_path))


def counted(native, inspected, dormant):
    match=net(native,'all status',r'(\d+) players, (\d+) inspected, (\d+) dormant')
    seen=(int(match.group(2)),int(match.group(3)))
    assert seen==(inspected,dormant), f'status shows {seen[0]} inspected and {seen[1]} dormant, expected {inspected} and {dormant}'


def dormant_round(args, native, lifecycle, participants, peers, transitions, case, round, current):
    """Turns checking off, lets the clients play, turns it on and switches everyone; the tapes then replay in full.
    Java peers are checked again at their switch; Bedrock clients stay unchecked until they reconnect."""
    total=args.players*2+args.java_peers
    online=args.players+args.java_peers
    for client,output in participants:
        request=output / f'switch-request-{round}.json'
        lifecycle.wait_for(lambda: client.poll() is not None or request.is_file(),180)
        assert client.poll() is None, (output / 'runner.log').read_text()
    if round==1: counted(native,online,0)
    off=net(native,'all toggle off',r'now dormant \((\d+) players not checked\)')
    assert int(off.group(1))==online, f'toggle off left {off.group(1)} of {online} players unchecked'
    counted(native,0,online)
    time.sleep(args.transfer_delay)
    on=net(native,'all toggle on',r'now checking \((\d+) players not checked yet')
    assert int(on.group(1))==online, f'toggle on reported {on.group(1)} of {online} players unchecked'
    runtime_status(native,current[:12]+f'.*CURRENT sessions={total} ')
    for i,(client,output) in enumerate(participants):
        switch=json.loads((output / f'switch-request-{round}.json').read_text())
        native.command('send '+switch['username']+' '+switch['target'])
        runtime_status(native,current[:12]+f'.*CURRENT sessions={total} ')
        (output / f'switch-complete-{round}.json').write_text(json.dumps({'previous':current,'current':current}))
        transitions.append({'case':case,'round':round,'player':i,'kind':'bedrock','target':switch['target'],
            'selectedHash':current,'sessions':total,'dormant':True})
        time.sleep(args.transfer_delay)
    for peer in peers:
        joins=peer.joins
        target_server='lobby' if round%2==0 else 'factions'
        native.command('send '+peer.name+' '+target_server)
        lifecycle.wait_for(lambda: peer.closed or peer.joins>joins)
        assert not peer.closed, 'Java peer disconnected at a switch while dormant'
        runtime_status(native,current[:12]+f'.*CURRENT sessions={total} ')
        transitions.append({'case':case,'round':round,'player':peer.name,'kind':'java','target':target_server,
            'selectedHash':current,'sessions':total,'dormant':True})
    counted(native,args.java_peers,args.players)


def java_peer_type(lifecycle):
    source=textwrap.dedent(inspect.getsource(lifecycle.Bot.read))
    line=next(line for line in source.splitlines() if "elif name == 'keep_alive':" in line)
    indent=line[:len(line)-len(line.lstrip())]
    terminal=indent+"elif name == 'start_configuration':\n"+indent+"    self.send('configuration_acknowledged')\n"+indent+"    self.state = 'configuration'\n"+line
    source=source.replace(line,terminal)
    source=source.replace("print(self.name + ' joined', flush=True)","self.joins += 1\n"+indent+"    print(self.name + ' joined', flush=True)")
    namespace=dict(lifecycle.__dict__)
    exec(source,namespace)
    def initialize(self,name,port):
        self.joins=0
        lifecycle.Bot.__init__(self,name,port)
    return type('HandoffJavaPeer',(lifecycle.Bot,),{'read':namespace['read'],'__init__':initialize})


def exercise(args, paper, velocity, lifecycle, folder):
    capture = paper.runtime(args.replay_root / 'runtime-lock.json')
    target = velocity.target_runtime(args.velocity_lock, capture)
    for case in args.cases:
        paper.capture_runtime(args.scenarios / case, capture)
    cache = folder / 'downloads'
    cache.mkdir()
    jars = {name: paper.replay_artifact(value, cache) for name, value in target['artifacts'].items()}
    cli, classes, observer, fixture = compile_probe(args, jars['velocity'], folder)
    proxy, first, second = (folder / name for name in ('proxy', 'backend-a', 'backend-b'))
    for server in (proxy, first, second):
        (server / 'plugins').mkdir(parents=True)
    velocity.configure(first, proxy, args.java_port + 1, args.java_port, args.bedrock_port)
    paper.configure(second, args.bedrock_port, args.java_port + 2)
    shutil.rmtree(second / 'plugins/Geyser-Spigot')
    shutil.rmtree(second / 'plugins/CultAC')
    (second / 'config').mkdir(exist_ok=True)
    shutil.copyfile(first / 'config/paper-global.yml', second / 'config/paper-global.yml')
    config = proxy / 'velocity.toml'
    config.write_text(config.read_text().replace(
        f'factions = "127.0.0.1:{args.java_port + 1}"',
        f'factions = "127.0.0.1:{args.java_port + 2}"'))
    for backend in (first, second):
        for name in ('viaversion', 'viabackwards'):
            shutil.copyfile(jars[name], backend / ('plugins/' + name + '.jar'))
        shutil.copyfile(fixture, backend / 'plugins/HandoffReplayFixture.jar')
        properties=backend / 'server.properties'
        properties.write_text(properties.read_text().replace('max-players=2', f'max-players={args.players+args.java_peers+2}'))
        if args.paper_cache:
            for name in ('libraries', 'versions', 'cache'):
                source = args.paper_cache / name
                if source.is_dir():
                    shutil.copytree(source, backend / name, dirs_exist_ok=True)
    shutil.copyfile(jars['geyser'], proxy / 'plugins/Geyser-Velocity.jar')
    shutil.copyfile(args.plugin, proxy / 'plugins/CultAC.jar')
    initial_plugin_hash=paper.digest(proxy / 'plugins/CultAC.jar')
    shutil.copyfile(observer, proxy / 'plugins/HandoffObserver.jar')
    children = []
    clients = []
    peers = []
    logs = []
    try:
        for backend in (first, second):
            children.append(lifecycle.Process(backend, jars['paper'], True))
            lifecycle.wait_for(lambda: (backend / 'plugins/HandoffReplayFixture/ready.json').is_file())
        native = lifecycle.Process(proxy, jars['velocity'])
        children.append(native)
        control = proxy / 'plugins/cult-bedrock-handoff-observer'
        lifecycle.wait_for(lambda: (control / 'ready.json').is_file())
        cult = proxy / 'plugins/cultac'
        engine = cult / 'updates/source.jar'
        with ZipFile(proxy / 'plugins/CultAC.jar') as source:
            engine.write_bytes(source.read('cult-runtime/default.jar'))
        current_hash = hashlib.sha256(engine.read_bytes()).hexdigest()
        transitions = []
        Peer = java_peer_type(lifecycle)
        for i in range(args.java_peers):
            peer = Peer('CultSwapPeer'+str(i), args.java_port)
            peers.append(peer)
            peer.ready.wait(30)
            assert peer.ready.is_set() and not peer.closed, 'Java peer failed login'
        for case in args.cases:
            participants=[]
            for i in range(args.players):
                name='client-'+str(i)
                output=folder / 'replays' / case / name
                output.mkdir(parents=True)
                observer_control=control / 'players' / name
                observer_control.mkdir(parents=True,exist_ok=True)
                fixture_controls=[server / 'plugins/HandoffReplayFixture/players' / name for server in (first,second)]
                for channel in fixture_controls:
                    channel.mkdir(parents=True,exist_ok=True)
                log=(output / 'runner.log').open('w');logs.append(log)
                client=subprocess.Popen([
                    'java', '-cp', str(classes)+':'+str(cli),
                    'ac.cultac.bedrockreplay.regression.LiveHandoffReplay',
                    str(args.scenarios / case), str(observer_control), str(output), str(args.bedrock_port),
                    str(fixture_controls[0]),str(fixture_controls[1]),str(args.rounds),
                ]+(['dormant'] if args.dormant else []),stdout=log,stderr=subprocess.STDOUT)
                clients.append(client);participants.append((client,output))
            for client,output in participants:
                lifecycle.wait_for(lambda: client.poll() is not None or (output / 'connected.json').is_file(),120)
                assert client.poll() is None, (output / 'runner.log').read_text()
            runtime_status(native,current_hash[:12]+f'.*CURRENT sessions={args.players*2+args.java_peers} ')
            # All physical sessions pin the old engine before gameplay begins. Later participants
            # start their complete tapes later, so old/new engines both process real movement during draining.
            for client,output in participants:
                (output / 'start.json').write_text('{}')
                time.sleep(args.transfer_delay)
            for round in range(1,args.rounds):
                if args.dormant:
                    dormant_round(args,native,lifecycle,participants,peers,transitions,case,round,current_hash)
                    continue
                first_client,first_output=participants[0]
                request=first_output / f'switch-request-{round}.json'
                lifecycle.wait_for(lambda: first_client.poll() is not None or request.is_file(),180)
                assert first_client.poll() is None, (first_output / 'runner.log').read_text()
                previous=current_hash
                update=cult / f'updates/switch-{len(transitions)}.jar'
                current_hash=lifecycle.updated_engine(engine,update,build=200+len(transitions))
                offset=len(native.log())
                native.command('cult update '+update.name)
                lifecycle.wait_for(lambda: 'Activated CultAC runtime' in native.log()[offset:])
                total=args.players*2+args.java_peers
                runtime_status(native,previous[:12]+f'.*DRAINING sessions={total} ')
                runtime_status(native,current_hash[:12]+'.*CURRENT sessions=0 ')
                moved=0;last_transfer=0
                for i,(client,output) in enumerate(participants):
                    request=output / f'switch-request-{round}.json'
                    lifecycle.wait_for(lambda: client.poll() is not None or request.is_file(),180)
                    assert client.poll() is None, (output / 'runner.log').read_text()
                    delay=args.transfer_delay-(time.monotonic()-last_transfer)
                    if delay>0: time.sleep(delay)
                    switch=json.loads(request.read_text())
                    before=len(native.log())
                    native.command('send '+switch['username']+' '+switch['target'])
                    moved+=2
                    runtime_status(native,current_hash[:12]+f'.*CURRENT sessions={moved} ')
                    remaining=total-moved
                    if remaining:
                        runtime_status(native,previous[:12]+f'.*DRAINING sessions={remaining} ')
                        assert 'Unloaded CultAC runtime' not in native.log()[offset:], 'Old engine unloaded with players still pinned'
                    else:
                        lifecycle.wait_for(lambda: 'Unloaded CultAC runtime' in native.log()[before:])
                    (output / f'switch-complete-{round}.json').write_text(json.dumps({'previous':previous,'current':current_hash}))
                    transitions.append({'case':case,'round':round,'player':i,'kind':'bedrock','target':switch['target'],
                        'previousHash':previous,'selectedHash':current_hash,'oldSessions':remaining,'newSessions':moved})
                    last_transfer=time.monotonic()
                for i,peer in enumerate(peers):
                    time.sleep(args.transfer_delay)
                    joins=peer.joins
                    before=len(native.log())
                    target_server='lobby' if round%2==0 else 'factions'
                    native.command('send '+peer.name+' '+target_server)
                    lifecycle.wait_for(lambda: peer.closed or peer.joins>joins)
                    assert not peer.closed, 'Java peer disconnected during handoff'
                    moved+=1
                    runtime_status(native,current_hash[:12]+f'.*CURRENT sessions={moved} ')
                    remaining=total-moved
                    if remaining:
                        runtime_status(native,previous[:12]+f'.*DRAINING sessions={remaining} ')
                        assert 'Unloaded CultAC runtime' not in native.log()[offset:]
                    else:
                        lifecycle.wait_for(lambda: 'Unloaded CultAC runtime' in native.log()[before:])
                    transitions.append({'case':case,'round':round,'player':peer.name,'kind':'java',
                        'previousHash':previous,'selectedHash':current_hash,'oldSessions':remaining,'newSessions':moved})
            for client,output in participants:
                lifecycle.wait_for(lambda: client.poll() is not None or (output / 'playback-complete.json').is_file(),180)
                assert client.poll() is None, (output / 'runner.log').read_text()
            runtime_status(native,current_hash[:12]+f'.*CURRENT sessions={args.players*2+args.java_peers} ')
            for client,output in participants:
                (output / 'disconnect.json').write_text('{}')
            for client,output in participants:
                client.wait(timeout=30)
                assert client.returncode==0, (output / 'runner.log').read_text()
                assert json.loads((output / 'result.json').read_text())['passed']
            runtime_status(native,current_hash[:12]+f'.*CURRENT sessions={args.java_peers} ')
            if args.dormant:
                print(case+f': {args.players} Bedrock players through {args.rounds-1} dormant rounds; full tapes PASS',flush=True)
            else:
                print(case+f': {args.players} Bedrock players gradually drained over {args.transfer_delay}s intervals; full tapes/mutations PASS',flush=True)
        for peer in peers: peer.close()
        runtime_status(native,current_hash[:12]+'.*CURRENT sessions=0 ')
        (folder / 'result.json').write_text(json.dumps({'passed':True,'transitions':transitions,
            'rounds':args.rounds,'cases':args.cases,'bedrockPlayers':args.players,'javaPeers':args.java_peers,
            'transferDelaySeconds':args.transfer_delay,'dormant':args.dormant,'runtime':target,'pluginSha256':initial_plugin_hash,
            'topology':'ViaVersion/ViaBackwards on both Paper backends; Geyser/Cult on Velocity'},indent=2))
    finally:
        for client in clients:
            if client.poll() is None:
                client.terminate();client.wait(timeout=15)
        for peer in peers: peer.close()
        for log in logs: log.close()
        for process in reversed(children):
            if process.proc.poll() is None: process.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay-root', type=Path, required=True)
    parser.add_argument('--scenarios', type=Path, required=True)
    parser.add_argument('--velocity-lock', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'velocity-bootstrap/build/libs/CultAC-velocity.jar')
    parser.add_argument('--artifact-root', type=Path, default=ROOT / 'build/hot-update-bedrock-switch')
    parser.add_argument('--paper-cache', type=Path)
    parser.add_argument('--cases', nargs='+', choices=('longcourse', 'elytra', 'elytra2'), default=['longcourse'])
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--players', type=int, default=1)
    parser.add_argument('--java-peers', type=int, default=0)
    parser.add_argument('--transfer-delay', type=float, default=10)
    parser.add_argument('--paper-api', type=Path)
    parser.add_argument('--java-port', type=int, default=26970)
    parser.add_argument('--bedrock-port', type=int, default=19870)
    parser.add_argument('--dormant', action='store_true',
                        help='Replace each engine update with `cult net all toggle off`, play, `toggle on` and a '
                             'backend switch; round 0 is checked, later rounds replay unchecked Bedrock clients')
    args = parser.parse_args()
    if args.players < 1 or args.java_peers < 0 or not 0 <= args.transfer_delay <= 45:
        parser.error('Use >=1 Bedrock player, >=0 Java peers and 0..45 second transfer intervals')
    if args.dormant and args.rounds < 2:
        parser.error('--dormant needs a checked round and at least one dormant round')
    if not args.dormant and args.rounds < 3:
        parser.error('--rounds must include two baseline rounds and at least one mutated round')
    for name in ('replay_root', 'scenarios', 'velocity_lock', 'plugin', 'artifact_root'):
        setattr(args, name, getattr(args, name).resolve())
    args.artifact_root.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix='run-', dir=args.artifact_root))
    print('Artifacts: ' + str(folder), flush=True)
    sys.path.insert(0, str(args.replay_root / 'scripts'))
    paper = module('handoff_paper', args.replay_root / 'scripts/run-movement-regressions.py')
    velocity = module('handoff_velocity', args.replay_root / 'scripts/run-velocity-movement-regressions.py')
    lifecycle = module('handoff_lifecycle', ROOT / 'scripts/smoketests/hot-update-lifecycle.py')
    exercise(args, paper, velocity, lifecycle, folder)


if __name__ == '__main__':
    main()
