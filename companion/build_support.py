"""Source staging shared by local builds and packaging; never copy device state."""
from pathlib import Path
import shutil

EXCLUDED_DIRS = {'.gradle', '.kotlin', 'build', '.idea', '__pycache__', '.git'}
EXCLUDED_FILES = {'local.properties', 'keystore.properties', 'signing.properties', '.env'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.apk', '.aab'}


def source_files(source):
    source = Path(source).resolve()
    for path in sorted(source.rglob('*')):
        rel = path.relative_to(source)
        if any(part in EXCLUDED_DIRS for part in rel.parts):
            continue
        if path.is_symlink():
            raise ValueError(f'Source symlinks are not supported: {rel}')
        if (path.is_file() and path.name not in EXCLUDED_FILES
                and not path.name.startswith('.env.') and path.suffix.lower() not in PRIVATE_SUFFIXES):
            yield path, rel


def stage_sources(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError('Build staging must be separate from the source tree')
    files = list(source_files(source))
    destination.mkdir(parents=True, exist_ok=True)
    # This directory is exclusively a generated source mirror. Removing it also
    # handles legacy stages created before inventory tracking existed. Retain
    # Gradle caches and build products, but never compile a deleted Kotlin file.
    target = destination / 'app' / 'src'
    if target.resolve() != target or not target.resolve().is_relative_to(destination):
        raise ValueError('Unsafe staging source path')
    if target.exists():
        shutil.rmtree(target)
    for path, rel in files:
        target = destination / rel
        if not target.resolve().is_relative_to(destination):
            raise ValueError('Staging path escapes its destination')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)

