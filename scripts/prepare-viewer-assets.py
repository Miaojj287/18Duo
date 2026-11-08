"""Build lossless gzip copies for static hosting, without changing model bytes."""
import gzip
from pathlib import Path
import sys


def prepare(root):
    source_bytes = compressed_bytes = count = 0
    for path in sorted((root / "assets/viewer/v").rglob("*")):
        if path.suffix not in {".bin", ".lsd", ".gltf"}:
            continue
        source = path.read_bytes()
        compressed = gzip.compress(source, compresslevel=9, mtime=0)
        assert gzip.decompress(compressed) == source
        path.with_name(path.name + ".gz").write_bytes(compressed)
        count += 1
        source_bytes += len(source)
        compressed_bytes += len(compressed)
    print(f"Viewer: {count} files, {source_bytes:,} → {compressed_bytes:,} bytes")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1])
