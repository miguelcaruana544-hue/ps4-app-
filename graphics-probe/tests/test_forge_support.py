#!/usr/bin/env python3
"""Host test of the Forge support: ForgeSupportTest against the changed launcher sources.
Mirrors tests/test_launcher.py's shape: it compiles the launcher with the adapter sources
on the source path (the lwjgl-orbis clone in CI, or ORBIS_STUBS locally) and runs the test."""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
# Gson 2.8.9, as installed under the built runtime tree (or overridden for local runs).
GSON = Path(os.environ.get('GSON_JAR', ROOT / 'build/minecraft-1.18.2/libraries/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar'))
# orbis.graphics sources (the adapter the launcher compiles against): CI's lwjgl-orbis clone, else stubs.
SOURCEPATH = Path(os.environ.get('ORBIS_STUBS', ROOT / 'lwjgl' / 'java'))
# LWJGL 3.2.2 Java JARs, as the launcher build prepares them (LWJGL_AUDIT like test_launcher.py).
AUDIT = Path(os.environ.get('LWJGL_AUDIT', '/tmp/lwjgl-audit'))
TWELVEMONKEYS = ROOT / 'build/downloads/twelvemonkeys'
XZ = ROOT / 'build/downloads/xz'

classpath = f'{GSON}'
for name in ('lwjgl-3.2.2.jar', 'lwjgl-opengl-3.2.2.jar', 'lwjgl-glfw-3.2.2.jar'):
    jar = AUDIT / name
    if jar.is_file():
        classpath += ':' + str(jar)
for folder in (TWELVEMONKEYS, XZ):
    if folder.is_dir():
        classpath += ''.join(':' + str(jar) for jar in sorted(folder.glob('*.jar')))

sources = sorted((ROOT / 'launcher/java').rglob('*.java'))
sources.append(ROOT / 'tests/launcher/orbis/launcher/ForgeSupportTest.java')
with tempfile.TemporaryDirectory() as directory:
    subprocess.run([str(JAVA / 'javac'), '--release', '17', '-encoding', 'UTF-8',
                    '-cp', classpath, '-sourcepath', str(SOURCEPATH), '-implicit:class',
                    '-d', directory, *map(str, sources)], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', f'{directory}:{classpath}', 'orbis.launcher.ForgeSupportTest'], check=True)
