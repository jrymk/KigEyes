import tempfile
import unittest
from pathlib import Path
from build_support import stage_sources, source_files


class StagingTest(unittest.TestCase):
    def test_deleted_source_is_removed_and_build_cache_retained(self):
        with tempfile.TemporaryDirectory() as root:
            source, stage = Path(root)/'source', Path(root)/'stage'
            for path, text in [(source/'app/src/main/A.kt', 'new'),
                               (stage/'app/src/main/Removed.kt', 'old'),
                               (stage/'app/build/cache', 'keep'),
                               (source/'local.properties', 'private')]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            stage_sources(source, stage)
            self.assertFalse((stage/'app/src/main/Removed.kt').exists())
            self.assertEqual((stage/'app/src/main/A.kt').read_text(), 'new')
            self.assertEqual((stage/'app/build/cache').read_text(), 'keep')
            self.assertFalse((stage/'local.properties').exists())

    def test_overlapping_paths_rejected_before_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root)/'source'
            source.mkdir()
            for stage in (source, source/'stage', source.parent):
                with self.assertRaises(ValueError):
                    stage_sources(source, stage)

    def test_generated_files_excluded(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root)
            for name in ('app/build/a.apk', '.gradle/state', '__pycache__/x.pyc', 'app/src/main/A.kt'):
                path = source/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            self.assertEqual([str(rel).replace('\\', '/') for _, rel in source_files(source)], ['app/src/main/A.kt'])

    def test_signing_and_environment_files_never_enter_source_bundle(self):
        with tempfile.TemporaryDirectory() as root:
            source=Path(root)
            for name in ('release.jks','release.keystore','identity.p12','private.pem',
                         'private.key','.env','.env.production','signing.properties',
                         'keystore.properties','download.apk','release.aab',
                         'gradle/wrapper/gradle-wrapper.jar'):
                path=source/name;path.parent.mkdir(parents=True,exist_ok=True);path.touch()
            self.assertEqual([rel.as_posix() for _,rel in source_files(source)],
                             ['gradle/wrapper/gradle-wrapper.jar'])


if __name__ == '__main__':
    unittest.main()
