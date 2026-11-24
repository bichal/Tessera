# Settings

NeoForge: the in-game mod settings, or `config/tessera-client.toml`. Fabric: `config/tessera.properties`
(written on first start; restart after editing it):

| Option | Default | Effect |
|---|---|---|
| `compressionQuality` | `4` | BC7 quality preset, `0` (fastest) to `7` (best) |
| `disableNativeCompression` | `false` | Turns compression off; atlases stay vanilla |
| `compressAnimatedAtlases` | `true` | Also compresses atlases with animated textures (the block atlas), with every frame encoded once; off keeps them RGBA8888. Ignored while Kerria is installed |
| `vramBudgetTargetMb` | `2048` | Budget used when the GPU's VRAM can't be detected; going over it only logs a warning (a compressed atlas always takes less than the vanilla one) |
| `cacheDirectory` | `tessera-cache` | Disk cache folder, relative to the game directory |

The disk cache is capped at 512 MB (of compressed data on disk); change it with `-Dtessera.cache.maxMB=<MB>`.

[Back to Home](Home.md)
