package net.bichal.mods.tessera.compress.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * BC7 on the GPU with a compute shader ({@code bc7_encode.comp}, next to this class): modes 6 and 5, and
 * the two-subset modes 1 and 3 (opaque blocks) and 7 (blocks with alpha) on the 1, 3 or 8 partitions with
 * the smallest PCA residual (presets 0-1, 2-4, 5-7). On the vanilla atlases it is within 0.2 dB of the
 * CPU encoder at the default preset, never moves a texel across the cutout threshold and keeps opaque
 * texels at alpha 255. The texels go in
 * and the blocks come back through shader storage buffers, so the only GL state touched is the current
 * program and the shader storage buffer bindings, all restored before returning; no texture unit,
 * image unit or framebuffer that Minecraft tracks is used.
 *
 * <p>Large images are split into several dispatches so no single one runs long enough to trip a driver
 * watchdog (Windows resets the GPU after two seconds). {@link #begin} goes further for compression threads:
 * the texels go up band by band, at most {@link #SLICES_IN_FLIGHT} dispatches are queued, each behind a
 * fence, and the blocks of a dispatch are read back only once its fence has signalled, so the render thread
 * never blocks on the GPU and keeps drawing frames between slices.
 */
public final class ComputeBc7Encoder implements GpuBc7Encoder {

    private static final String SHADER = "bc7_encode.comp";
    private static final int LOCAL_SIZE = 64;
    /** Blocks per dispatch: a 1024x1024 region. */
    private static final int BLOCKS_PER_DISPATCH = 65536;
    /** Dispatches of one job queued on the GPU at once: one running, one ready behind it. */
    private static final int SLICES_IN_FLIGHT = 2;

    private int program;
    private int uWidth, uHeight, uBlocksX, uBlockCount, uBlockOffset, uFlags, uCandidates;

    @Override
    public String name() {
        return "compute shader (BC7 modes 1, 3, 5, 6, 7)";
    }

    @Override
    public ByteBuffer encode(ByteBuffer rgba8, int width, int height, int qualityPreset) {
        if (width <= 0 || height <= 0 || rgba8.remaining() < width * height * 4) {
            return null;
        }
        ensureProgram();
        int blocksX = (width + 3) / 4;
        int blockCount = blocksX * ((height + 3) / 4);

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousGeneric = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int previous0 = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, 0);
        int previous1 = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, 1);
        int source = GL15.glGenBuffers();
        int blocks = GL15.glGenBuffers();
        try {
            ByteBuffer texels = rgba8.slice(rgba8.position(), width * height * 4);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, source);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, texels, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blocks);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) blockCount * 16, GL15.GL_STREAM_READ);

            bindPass(width, height, blocksX, blockCount, candidatesFor(qualityPreset), source, blocks);
            for (int offset = 0; offset < blockCount; offset += BLOCKS_PER_DISPATCH) {
                int count = Math.min(BLOCKS_PER_DISPATCH, blockCount - offset);
                GL30.glUniform1ui(uBlockOffset, offset);
                GL43.glDispatchCompute((count + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
                GL11.glFlush();
            }
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);

            ByteBuffer out = ByteBuffer.allocateDirect(blockCount * 16).order(ByteOrder.LITTLE_ENDIAN);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blocks);
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, out);
            return GL11.glGetError() == GL11.GL_NO_ERROR ? out : null;
        } finally {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, previous0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, previous1);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousGeneric);
            GL20.glUseProgram(previousProgram);
            GL15.glDeleteBuffers(source);
            GL15.glDeleteBuffers(blocks);
        }
    }

    @Override
    public Job begin(ByteBuffer rgba8, int width, int height, int qualityPreset) {
        if (width <= 0 || height <= 0 || rgba8.remaining() < width * height * 4) {
            return null;
        }
        ensureProgram();
        return new SlicedJob(rgba8.slice(rgba8.position(), width * height * 4), width, height,
                candidatesFor(qualityPreset));
    }

    private final class SlicedJob implements Job {

        private final ByteBuffer texels;
        private final int width, height, blocksX, blockCount, candidates;
        private final ByteBuffer out;
        /** Dispatched and not read back yet, oldest first: {fence, first block, block count}. */
        private final ArrayDeque<long[]> inFlight = new ArrayDeque<>();
        private int source, blocks;
        /** First block not dispatched yet, and texel rows already uploaded. */
        private int next, uploadedRows, back;

        SlicedJob(ByteBuffer texels, int width, int height, int candidates) {
            this.texels = texels;
            this.width = width;
            this.height = height;
            this.blocksX = (width + 3) / 4;
            this.blockCount = blocksX * ((height + 3) / 4);
            this.candidates = candidates;
            this.out = ByteBuffer.allocateDirect(blockCount * 16).order(ByteOrder.LITTLE_ENDIAN);
            int previousGeneric = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            source = GL15.glGenBuffers();
            blocks = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, source);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) width * height * 4, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blocks);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) blockCount * 16, GL15.GL_STREAM_READ);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousGeneric);
        }

        @Override
        public boolean advance() {
            if (source == 0) {
                throw new IllegalStateException("the GPU encode was released");
            }
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousGeneric = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            int previous0 = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, 0);
            int previous1 = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, 1);
            try {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blocks);
                while (!inFlight.isEmpty()) {
                    long[] slice = inFlight.peekFirst();
                    int state = GL32.glClientWaitSync(slice[0], 0, 0);
                    if (state == GL32.GL_TIMEOUT_EXPIRED) {
                        break;
                    }
                    if (state == GL32.GL_WAIT_FAILED) {
                        throw new IllegalStateException("waiting on a GPU encode fence failed");
                    }
                    inFlight.removeFirst();
                    GL32.glDeleteSync(slice[0]);
                    GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, slice[1] * 16,
                            out.slice((int) slice[1] * 16, (int) slice[2] * 16));
                    back += (int) slice[2];
                }
                if (next < blockCount && inFlight.size() < SLICES_IN_FLIGHT) {
                    bindPass(width, height, blocksX, blockCount, candidates, source, blocks);
                    while (next < blockCount && inFlight.size() < SLICES_IN_FLIGHT) {
                        int count = Math.min(BLOCKS_PER_DISPATCH, blockCount - next);
                        // The texel rows this slice reads that are not up yet (bands never overlap).
                        int rows = Math.min(height, ((next + count - 1) / blocksX + 1) * 4);
                        if (rows > uploadedRows) {
                            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, source);
                            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) uploadedRows * width * 4,
                                    texels.slice(uploadedRows * width * 4, (rows - uploadedRows) * width * 4));
                            uploadedRows = rows;
                        }
                        GL30.glUniform1ui(uBlockOffset, next);
                        GL43.glDispatchCompute((count + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
                        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                        inFlight.addLast(new long[]{GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0), next, count});
                        next += count;
                    }
                    GL11.glFlush();
                }
                if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                    throw new IllegalStateException("GL reported an error during a GPU encode");
                }
                return next >= blockCount && inFlight.isEmpty();
            } finally {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, previous0);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, previous1);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousGeneric);
                GL20.glUseProgram(previousProgram);
            }
        }

        @Override
        public int blocksBack() {
            return back;
        }

        @Override
        public ByteBuffer blocks() {
            return out;
        }

        @Override
        public void release() {
            for (long[] slice : inFlight) {
                GL32.glDeleteSync(slice[0]);
            }
            inFlight.clear();
            if (source != 0) {
                GL15.glDeleteBuffers(source);
                GL15.glDeleteBuffers(blocks);
                source = 0;
                blocks = 0;
            }
        }
    }

    /** Endpoint candidates the shader tries per block for a quality preset. */
    private static int candidatesFor(int qualityPreset) {
        return qualityPreset <= 1 ? 1 : (qualityPreset <= 4 ? 3 : 8);
    }

    /** Binds the program, its uniforms and both buffers for a run of dispatches. */
    private void bindPass(int width, int height, int blocksX, int blockCount, int candidates, int source, int blocks) {
        GL20.glUseProgram(program);
        GL30.glUniform1ui(uWidth, width);
        GL30.glUniform1ui(uHeight, height);
        GL30.glUniform1ui(uBlocksX, blocksX);
        GL30.glUniform1ui(uBlockCount, blockCount);
        GL30.glUniform1ui(uFlags, 1);
        GL30.glUniform1ui(uCandidates, candidates);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, source);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, blocks);
    }

    /** Releases the program; the next encode compiles it again. Render thread. */
    public void close() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }

    private void ensureProgram() {
        if (program != 0) {
            return;
        }
        String source;
        try (InputStream in = ComputeBc7Encoder.class.getResourceAsStream(SHADER)) {
            if (in == null) {
                throw new IllegalStateException(SHADER + " is missing from the jar");
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SHADER, e);
        }
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("the BC7 compute shader does not compile: " + log.strip());
        }
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, shader);
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("the BC7 compute shader does not link: " + log.strip());
        }
        program = linked;
        uWidth = GL20.glGetUniformLocation(program, "uWidth");
        uHeight = GL20.glGetUniformLocation(program, "uHeight");
        uBlocksX = GL20.glGetUniformLocation(program, "uBlocksX");
        uBlockCount = GL20.glGetUniformLocation(program, "uBlockCount");
        uBlockOffset = GL20.glGetUniformLocation(program, "uBlockOffset");
        uFlags = GL20.glGetUniformLocation(program, "uFlags");
        uCandidates = GL20.glGetUniformLocation(program, "uCandidates");
    }
}
