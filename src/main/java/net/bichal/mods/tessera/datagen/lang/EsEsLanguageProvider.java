//? if neoforge {
package net.bichal.mods.tessera.datagen.lang;

import net.minecraft.data.PackOutput;

public class EsEsLanguageProvider extends LanguageProvider {

    public EsEsLanguageProvider(PackOutput output) {
        super(output, "es_es");
    }

    @Override
    protected void addTranslations() {
        // Mod y título
        add("modmenu.name.tessera", "Tessera");
        add("modmenu.description.tessera", "Motor nativo de optimización de VRAM y compresión de texturas a BC7 en GPU.");

        // Títulos de pantalla de configuración requeridos por NeoForge
        add("tessera.configuration.title", "Configuración de Tessera");
        add("tessera.configuration.section.tessera.client.toml", "Configuración de Cliente de Tessera");
        add("tessera.configuration.section.tessera.client.toml.title", "Configuración de Cliente de Tessera");

        // Categorías (Título, Tooltip y Botones de submenú)
        addConfigCategory("compression", "Compresión", "Ajustes de compresión nativa BC7 y congelación de animaciones.", "Ajustes de Compresión");
        addConfigCategory("deduplication", "Desduplicación", "Ajustes de hashing perceptual y fusión de sprites duplicados.", "Ajustes de Desduplicación");
        addConfigCategory("vramBudget", "Límite de VRAM", "Cuánta VRAM puede usar Tessera para los atlas comprimidos y el banco de frames de animación.", "Ajustes de Límite de VRAM");
        addConfigCategory("cache", "Caché", "Ajustes de la ubicación del almacenamiento en disco.", "Ajustes de Caché");
        addConfigCategory("debug", "Depuración", "Ajustes de visualización e información avanzada en la pantalla F3.", "Ajustes de Depuración");

        // Opciones de Compresión
        addConfigOption("compressionQuality", "Calidad de Compresión",
                "Preajuste de calidad BC7 (0: Más rápido a 7: Máxima fidelidad). Controla la calidad de compresión contra el tiempo de procesamiento.");
        addConfigOption("disableNativeCompression", "Desactivar Compresión Nativa",
                "Fuerza el comportamiento RGBA vanilla aunque la compresión BC7 esté soportada por la GPU.");

        addConfigOption("compressAnimatedAtlases", "Comprimir Atlas Animados",
                "Comprime también los atlas con texturas animadas, como el de bloques: cada frame se codifica una vez. Desactivado los deja en RGBA8888. Se ignora si Kerria está instalado.");

        addConfigOption("gpuCompression", "Compresión por GPU (Experimental)",
                "EXPERIMENTAL: codifica BC7 en la GPU en vez de en la CPU. Necesita OpenGL 4.3 (no disponible en macOS). Puede verse peor, dar tirones o cerrar el juego con algunos drivers; Tessera la comprueba al arrancar y sigue en la CPU si la comprobación falla. También guarda los frames de las animaciones en la VRAM (hasta 64 MB por atlas) y los copia en la GPU en vez de subirlos en cada tick. F3 muestra cuál se está usando.");

        addConfigOption("compressGuiAtlas", "Comprimir Atlas de la GUI (Experimental)",
                "EXPERIMENTAL: comprime también el atlas de la GUI (iconos, barra rápida, punto de mira), alrededor de 1 MB en vanilla y mucho más con paquetes de recursos grandes. Los píxeles transparentes se escriben para que se decodifiquen exactamente a cero y el punto de mira quede limpio; algunos bordes de iconos pueden perder algo de detalle de color. Solo formatos BC.");

        // Opciones de Desduplicación
        addConfigOption("dedupSimilarityThreshold", "Umbral de Similitud",
                "Distancia Hamming (0-64) para huellas pHash de 64 bits. Valores más bajos son más estrictos al fusionar sprites duplicados.");

        // Opciones de Límite de VRAM
        addConfigOption("vramBudgetTargetMb", "Límite de VRAM (MB)",
                "VRAM que puede usar Tessera para los atlas comprimidos y el banco de frames de animación. El banco de frames solo crece dentro de él; un atlas que lo supere se comprime igualmente (sin comprimir ocuparía más), con un aviso en el registro. Se ignora con el Límite Automático activado si la GPU informa de su VRAM.");
        addConfigOption("vramBudgetAuto", "Límite Automático",
                "Calcula el límite a partir de la GPU: tres cuartas partes de su VRAM dedicada (NVIDIA) o de la VRAM libre al arrancar (AMD). Si el driver no la indica, se usa el valor de Límite de VRAM.");

        // Opciones de Caché
        addConfigOption("cacheDirectory", "Directorio de Caché",
                "Nombre de la carpeta donde se almacenan en disco los bloques comprimidos en BC7.");

        // Opciones de Depuración
        addConfigOption("showExtendedDebugBreakdown", "Mostrar Desglose Avanzado de Atlas",
                "Muestra el desglose detallado per-atlas y per-bucket en la pantalla de depuración F3.");

        // Teclas y mensajes Debug
        // Botones de la pantalla de ajustes (Fabric)
        add("tessera.configuration.undo", "Deshacer cambios");
        add("tessera.configuration.reset", "Restablecer valor por defecto");
        add("tessera.configuration.undoAll", "Deshacer cambios");
        add("tessera.configuration.resetAll", "Restablecer valores");

        add("key.categories.tessera", "Tessera");
        add("key.tessera.toggle_extended_debug", "Alternar Información Avanzada de Atlas");
        add("tessera.debug.advanced_atlas_info", "Info avanzada de atlas: %s");

        // Pantalla Debug
        add("tessera.overlay.vram_saved", "VRAM Ahorrada: %s MB (BC7 Activo)");
        // Minisculas para coincidir con el vanilla
        add("debug.state.hidden", "ocultado");
        add("debug.state.shown", "mostrado");
    }
}
//?}
