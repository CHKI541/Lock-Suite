#!/usr/bin/env python3
"""Copia el repo a una carpeta de compilación SOLO para el contenedor de IA (ver README.md).

Nunca toca el repo: excluye .git y builds, y en la COPIA
  - saca `javax.net.ssl.trustStoreType=WINDOWS-ROOT` de gradle.properties (en Linux
    rompe el wrapper con un error de "trust store"),
  - escribe local.properties con el SDK del contenedor y un keystore de mentira,
  - pone un google-services.json de mentira (el real no está en git, y no hace falta
    para compilar ni para las pruebas unitarias).
Se puede correr las veces que haga falta: solo copia lo que cambió.
"""
import filecmp, json, os, shutil, subprocess, sys

SRC = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DST = os.environ.get("LS_BUILD_DIR", "/home/claude/build/ls")
KEYSTORE = os.environ.get("LS_DUMMY_KEYSTORE", "/home/claude/build/dummy.jks")
SDK = os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk")
EXCL_DIRS = {".git", "_to_delete", "docs_historicos", "build", ".gradle", ".kotlin",
             "node_modules", "scratch", "Claude outputs", "release-apk"}
EXCL_ROOT_FILES = {"local.properties", "gradle.properties"}

changed = 0
seen = set()
for root, dirs, files in os.walk(SRC):
    dirs[:] = [d for d in dirs if d not in EXCL_DIRS]
    rel = os.path.relpath(root, SRC)
    for f in files:
        if rel == "." and f in EXCL_ROOT_FILES:
            continue
        if f.endswith(".exe"):
            continue
        s = os.path.join(root, f)
        r = os.path.normpath(os.path.join(rel, f))
        d = os.path.join(DST, r)
        seen.add(r)
        if not os.path.exists(d) or not filecmp.cmp(s, d, shallow=False):
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
            changed += 1
for root, dirs, files in os.walk(DST):
    dirs[:] = [d for d in dirs if d not in EXCL_DIRS]
    rel = os.path.relpath(root, DST)
    for f in files:
        r = os.path.normpath(os.path.join(rel, f))
        if rel == "." and f in EXCL_ROOT_FILES:
            continue
        if r == os.path.normpath("app/google-services.json"):
            continue
        if r not in seen:
            os.remove(os.path.join(root, f))
            changed += 1

lines = [l for l in open(os.path.join(SRC, "gradle.properties"), encoding="utf-8").read().splitlines()
         if "WINDOWS-ROOT" not in l and not l.startswith("org.gradle.jvmargs")]
lines.append("org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8")
open(os.path.join(DST, "gradle.properties"), "w").write("\n".join(lines) + "\n")

if not os.path.exists(KEYSTORE):
    os.makedirs(os.path.dirname(KEYSTORE), exist_ok=True)
    subprocess.run(["keytool", "-genkeypair", "-keystore", KEYSTORE, "-alias", "dummy",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                    "-storepass", "dummypass", "-keypass", "dummypass",
                    "-dname", "CN=dummy"], check=True, capture_output=True)
open(os.path.join(DST, "local.properties"), "w").write(
    "sdk.dir=%s\nRELEASE_STORE_FILE=%s\nRELEASE_STORE_PASSWORD=dummypass\n"
    "RELEASE_KEY_ALIAS=dummy\nRELEASE_KEY_PASSWORD=dummypass\n" % (SDK, KEYSTORE))

gs = {
    "project_info": {"project_number": "123456789012",
                     "firebase_url": "https://dummy-default-rtdb.firebaseio.com",
                     "project_id": "dummy-project", "storage_bucket": "dummy-project.appspot.com"},
    "client": [{
        "client_info": {"mobilesdk_app_id": "1:123456789012:android:0123456789abcdef",
                        "android_client_info": {"package_name": "com.ejemplo.locksuite"}},
        "oauth_client": [],
        "api_key": [{"current_key": "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUM"}],
        "services": {"appinvite_service": {"other_platform_oauth_client": []}}}],
    "configuration_version": "1"}
os.makedirs(os.path.join(DST, "app"), exist_ok=True)
json.dump(gs, open(os.path.join(DST, "app", "google-services.json"), "w"), indent=2)
print("copia de compilación: %d archivos cambiados → %s" % (changed, DST))
