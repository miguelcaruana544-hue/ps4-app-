#!/usr/bin/env python3
"""Host test of the built-in controller bridge: compiles the compatibility mod against fake
Minecraft classes in every mapping set it must handle (Mojang names, Forge's two SRG eras) and
checks what a scripted controller makes the game do. Needs only the JDK - no Fabric, no console.
Then runs the Forge agent end to end: java -javaagent against the 1.14-1.16 fakes."""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
COMPAT = ROOT / 'launcher/compat/java'
FAMILIES = ['compat', 'compat-srg116', 'compat-srg120']


def run(command, **kw):
    subprocess.run([str(c) for c in command], check=True, **kw)


def main():
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        compat_classes = tmp / 'compat-classes'
        compat_classes.mkdir()
        # Only the two classes the controller needs: they depend on no mixin or game API.
        controller_sources = [COMPAT / 'orbis/compat/ControllerBridge.java',
                              COMPAT / 'orbis/compat/ControllerAgent.java']
        run([JAVA / 'javac', '--release', '8', '-encoding', 'UTF-8', '-proc:none',
             '-d', compat_classes, *controller_sources])
        for family in FAMILIES:
            sources = sorted((ROOT / 'tests' / family).rglob('*.java'))
            sources = [s for s in sources if s.name != 'TextEntryBookTest.java']
            classes = tmp / family
            classes.mkdir()
            run([JAVA / 'javac', '--release', '17', '-encoding', 'UTF-8',
                 '-cp', compat_classes, '-d', classes, *sources])
            test = 'ControllerBridgeMojangTest' if family == 'compat' else \
                'ControllerBridgeSrg116Test' if family == 'compat-srg116' else 'ControllerBridgeSrg120Test'
            print(f'--- {test} ({family}) ---')
            try:
                run([JAVA / 'java', '-cp', f'{classes}:{compat_classes}', test])
            except subprocess.CalledProcessError:
                failures.append(family)

        # The agent path: Forge runs without mixins, the launcher starts the compat JAR with
        # -javaagent and ControllerAgent drives the bridge from its own thread.
        agent_jar = tmp / 'orbis-compat.jar'
        manifest = tmp / 'manifest.mf'
        manifest.write_bytes(b'Manifest-Version: 1.0\r\nPremain-Class: orbis.compat.ControllerAgent\r\n'
                             b'Agent-Class: orbis.compat.ControllerAgent\r\n\r\n')
        classes = tmp / 'compat-srg116'
        subprocess.run([str(JAVA / 'jar'), '--create', '--file', str(agent_jar), '--manifest', str(manifest),
                        '-C', str(classes), '.'], check=True)
        print('--- ControllerBridgeAgentTest (compat-srg116 + -javaagent) ---')
        try:
            run([JAVA / 'java', f'-javaagent:{agent_jar}', '-cp', f'{classes}:{compat_classes}',
                 'ControllerBridgeAgentTest'])
        except subprocess.CalledProcessError:
            failures.append('agent')

    if failures:
        print('failed families: ' + ', '.join(failures))
        sys.exit(1)
    print('ControllerBridge host tests: all families passed')


if __name__ == '__main__':
    main()
