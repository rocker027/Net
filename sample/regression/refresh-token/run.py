#!/usr/bin/env python3
"""用實際 interceptor 原始碼執行隔離回歸；不啟動 Android 或外部網路。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group, artifact, version):
    matches = list((CACHE / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise SystemExit(f"缺少 Gradle 快取：{group}:{artifact}:{version}；請先解析專案依賴，詳見 README.md")
    return str(matches[0])


java_home = os.environ.get("JAVA_HOME")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
if not java:
    raise SystemExit("找不到 Java，請設定 JAVA_HOME（專案基線為 JDK 11）")
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.8.10")
compiler = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.8.10"),
    stdlib,
    jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
    jar("org.jetbrains", "annotations", "13.0"),
]
runtime = [
    stdlib,
    jar("com.squareup.okhttp3", "okhttp", "4.10.0"),
    jar("com.squareup.okhttp3", "mockwebserver", "4.10.0"),
    jar("com.squareup.okio", "okio-jvm", "3.0.0"),
    jar("junit", "junit", "4.13.2"),
]
source = REPO / "sample/src/main/java/com/drake/net/sample/interceptor/RefreshTokenInterceptor.kt"
with tempfile.TemporaryDirectory(prefix="net-refresh-token-") as build_dir:
    output = str(Path(build_dir) / "regression.jar")
    subprocess.run([
        java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", os.pathsep.join(runtime),
        "-d", output, str(source), *map(str, sorted(HERE.glob("*.kt"))),
    ], check=True)
    subprocess.run([
        java, "-cp", os.pathsep.join([output] + runtime), "SampleRegressionKt",
    ], check=True)
