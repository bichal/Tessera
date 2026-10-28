# Settings

In the in-game mod settings, or `config/tessera-client.toml`:

| Option | Default | Effect |
|---|---|---|
| `compressionQuality` | `4` | BC7 quality preset, `0` (fastest) to `7` (best) |
| `disableNativeCompression` | `false` | Turns compression off; atlases stay vanilla |
| `vramBudgetTargetMb` | `2048` | Budget used when the GPU's VRAM can't be detected |
| `cacheDirectory` | `tessera-cache` | Disk cache folder, relative to the game directory |

The disk cache is capped at 512 MB; change it with `-Dtessera.cache.maxMB=<MB>`.

[Back to Home](Home.md)
