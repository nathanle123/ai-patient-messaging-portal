"""Download a pinned Hugging Face model snapshot at image build time.

Usage: python scripts/download_model.py <repo_id> <revision> <target_dir> <glob> [<glob> ...]
Runtime containers set HF_HUB_OFFLINE=1 and load from <target_dir>, so nothing is fetched at startup.
"""

import sys

from huggingface_hub import snapshot_download


def main() -> None:
    repo_id, revision, target, *patterns = sys.argv[1:]
    if len(revision) != 40:
        raise SystemExit(f"revision must be a full commit hash, got {revision!r}")
    path = snapshot_download(repo_id=repo_id, revision=revision, local_dir=target, allow_patterns=patterns or None)
    print(f"downloaded {repo_id}@{revision[:8]} to {path}")


if __name__ == "__main__":
    main()
