#!/usr/bin/env python3
"""预取并校验 Robolectric 所需的 Android SDK artifact。

Robolectric 4.14.1 的 MavenArtifactFetcher 只在本地仓库已存在 jar 时才跳过
下载，否则用 Guava 做一次性下载，既无重试也无分块校验。CI 上 200MB 级的
android-all-instrumented jar 一旦被截断，就会以
"SHA-512 mismatch for POM file"（消息本身是上游的复制粘贴 bug，实际校验的是 jar）
的形式永久失败。

本脚本把 artifact 预先放进本地 Maven 仓库（~/.m2/repository），Robolectric
随后命中本地缓存，完全跳过自己的下载器。

用法：
    python3 tools/prefetch_robolectric_sdks.py [--local-repo DIR] [--retries N]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

GROUP_PATH = "org/robolectric/android-all-instrumented"

# (artifact 版本, 期望 sha512)
# 版本拼装规则见 Robolectric DefaultSdkProvider：
#   "<androidVersion>-robolectric-<robolectricVersion>-i7"
#   i7 = PREINSTRUMENTED_VERSION
ARTIFACTS = (
    (
        "9-robolectric-4913185-2-i7",
        "24687b9ea919eb20f5e27e47274f2aa711e98eb332d4769d34044a3b4952a7139"
        "3831d0a3f61ba726dc8fe220673a76138c11a0716f304999e5b029146fe88eb",
    ),
    (
        "14-robolectric-10818077-i7",
        "7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724e"
        "ff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455",
    ),
    (
        "15-robolectric-12650502-i7",
        "87bdd0341838f5f630e1ff412ab29cb2f06e0f8cb7751f14679d11b4ee6084873"
        "001f8f6a8581ae6e139fa7c9a654b632412827430f984ebecc3c2588674f7e7",
    ),
)

REPOSITORIES = (
    "https://repo1.maven.org/maven2",
    "https://maven.aliyun.com/repository/public",
)


def sha512_of(path: Path) -> str:
    digest = hashlib.sha512()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def default_local_repo() -> Path:
    override = os.environ.get("maven.repo.local")
    if override:
        return Path(override)
    settings = Path.home() / ".m2" / "settings.xml"
    if settings.is_file():
        import re

        match = re.search(r"<localRepository>(.*?)</localRepository>", settings.read_text())
        if match:
            return Path(match.group(1).strip())
    return Path.home() / ".m2" / "repository"


def download(url: str, dest: Path, timeout: int = 600) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "douai-ci-prefetch"})
    with urllib.request.urlopen(request, timeout=timeout) as response, dest.open("wb") as out:
        shutil.copyfileobj(response, out, length=1 << 20)


def fetch_one(version: str, expected: str, local_repo: Path, retries: int) -> Path:
    """下载单个 artifact，校验 sha512 后原子地放入本地仓库。"""
    base = f"{GROUP_PATH}/{version}"
    jar_name = f"android-all-instrumented-{version}.jar"
    target_dir = local_repo / base
    target = target_dir / jar_name

    if target.is_file() and sha512_of(target) == expected:
        print(f"[skip] {jar_name} already cached and verified")
        return target

    target_dir.mkdir(parents=True, exist_ok=True)
    last_error = ""
    for attempt in range(1, retries + 1):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_jar = Path(tmp) / jar_name
            for repository in REPOSITORIES:
                url = f"{repository}/{base}/{jar_name}"
                try:
                    print(f"[try {attempt}/{retries}] downloading {url}")
                    download(url, tmp_jar)
                except Exception as exc:  # noqa: BLE001 - 记录后换源重试
                    last_error = f"{url}: {exc}"
                    print(f"[warn] {last_error}")
                    continue

                actual = sha512_of(tmp_jar)
                if actual != expected:
                    last_error = (
                        f"{url}: sha512 mismatch "
                        f"(expected {expected}, actual {actual}, "
                        f"size {tmp_jar.stat().st_size})"
                    )
                    print(f"[warn] {last_error}")
                    continue

                shutil.move(str(tmp_jar), str(target))
                print(f"[ok] {jar_name} verified and installed")
                return target

    raise RuntimeError(f"failed to fetch {version}: {last_error}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-repo", default=None)
    parser.add_argument("--retries", type=int, default=3)
    args = parser.parse_args(argv)

    local_repo = Path(args.local_repo) if args.local_repo else default_local_repo()
    local_repo.mkdir(parents=True, exist_ok=True)
    print(f"local maven repository: {local_repo}")

    failures = []
    for version, expected in ARTIFACTS:
        try:
            fetch_one(version, expected, local_repo, args.retries)
        except Exception as exc:  # noqa: BLE001 - 汇总后统一报错
            failures.append(str(exc))
            print(f"[error] {exc}", file=sys.stderr)

    if failures:
        print(f"failed: {len(failures)}/{len(ARTIFACTS)} artifacts", file=sys.stderr)
        return 1
    print(f"all {len(ARTIFACTS)} artifacts ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
