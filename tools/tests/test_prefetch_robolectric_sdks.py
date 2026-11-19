"""Guards the Robolectric SDK prefetch helper used by CI.

The prefetch step exists because Robolectric 4.14.1 downloads 100-200MB
android-all-instrumented jars with a single non-resumable request and no
retry. A truncated download surfaces as a confusing "SHA-512 mismatch for POM
file" assertion, so CI pre-seeds the local Maven repository instead.
"""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prefetch_robolectric_sdks as prefetch


class PrefetchRobolectricSdksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)

    def test_every_artifact_declares_a_full_length_sha512(self):
        self.assertTrue(prefetch.ARTIFACTS)
        for version, expected in prefetch.ARTIFACTS:
            self.assertEqual(128, len(expected), f"{version} sha512 is truncated")
            int(expected, 16)

    def test_artifact_versions_follow_the_robolectric_naming_scheme(self):
        for version, _ in prefetch.ARTIFACTS:
            self.assertRegex(version, r"^\d+-robolectric-\d+(-\d+)?-i\d+$")

    def test_cached_artifact_with_matching_hash_is_not_downloaded_again(self):
        version, expected = prefetch.ARTIFACTS[-1]
        target_dir = self.repo / prefetch.GROUP_PATH / version
        target_dir.mkdir(parents=True)
        jar = target_dir / f"android-all-instrumented-{version}.jar"
        jar.write_bytes(b"cached")

        original = prefetch.sha512_of

        def fake_sha512(path):
            return expected if path == jar else original(path)

        prefetch.sha512_of = fake_sha512
        self.addCleanup(setattr, prefetch, "sha512_of", original)

        calls = []
        original_download = prefetch.download

        def fake_download(url, dest, timeout=600):
            calls.append(url)
            original_download(url, dest, timeout)

        prefetch.download = fake_download
        self.addCleanup(setattr, prefetch, "download", original_download)

        result = prefetch.fetch_one(version, expected, self.repo, retries=1)
        self.assertEqual(jar, result)
        self.assertEqual([], calls, "verified cache entries must not be downloaded again")

    def test_truncated_download_is_rejected_and_retried_from_another_mirror(self):
        version, expected = prefetch.ARTIFACTS[-1]
        attempts = []

        def fake_download(url, dest, timeout=600):
            attempts.append(url)
            dest.write_bytes(b"truncated" * 8)

        original_download = prefetch.download
        prefetch.download = fake_download
        self.addCleanup(setattr, prefetch, "download", original_download)

        with self.assertRaises(RuntimeError):
            prefetch.fetch_one(version, expected, self.repo, retries=2)

        self.assertGreaterEqual(len(attempts), 2)
        self.assertFalse(
            (self.repo / prefetch.GROUP_PATH / version /
             f"android-all-instrumented-{version}.jar").exists(),
            "a file that failed verification must never be installed",
        )

    def test_verified_download_is_installed_into_the_local_repository(self):
        version, expected = prefetch.ARTIFACTS[-1]
        payload = b"x" * 1024

        def fake_download(url, dest, timeout=600):
            dest.write_bytes(payload)

        original_download = prefetch.download
        prefetch.download = fake_download
        self.addCleanup(setattr, prefetch, "download", original_download)

        original_sha512 = prefetch.sha512_of

        def fake_sha512(path):
            if Path(path).name.startswith("android-all-instrumented-"):
                return expected
            return original_sha512(path)

        prefetch.sha512_of = fake_sha512
        self.addCleanup(setattr, prefetch, "sha512_of", original_sha512)

        result = prefetch.fetch_one(version, expected, self.repo, retries=1)
        self.assertTrue(result.is_file())
        self.assertEqual(payload, result.read_bytes())

    def test_local_repository_respects_the_maven_repo_local_override(self):
        previous = prefetch.os.environ.get("maven.repo.local")
        prefetch.os.environ["maven.repo.local"] = str(self.repo / "custom")
        self.addCleanup(
            lambda: prefetch.os.environ.__setitem__("maven.repo.local", previous)
            if previous
            else prefetch.os.environ.pop("maven.repo.local", None)
        )
        self.assertEqual(self.repo / "custom", prefetch.default_local_repo())


if __name__ == "__main__":
    unittest.main()
