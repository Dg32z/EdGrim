"""WatchNeko cloud service: the other end of the plugin's HTTP contract.

The wire shapes live in :mod:`app.schemas` and are pinned by the shared corpus in ``contract/fixtures``.
Nothing here implements detection: the scorer, storage and model loading are tickets #11-#16.
"""
